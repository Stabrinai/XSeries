package com.cryptomorin.xseries.profiles.builder;

import com.cryptomorin.xseries.profiles.ProfilesCore;
import com.cryptomorin.xseries.profiles.exceptions.*;
import com.cryptomorin.xseries.profiles.mojang.PlayerProfileFetcherThread;
import com.cryptomorin.xseries.profiles.objects.ProfileContainer;
import com.cryptomorin.xseries.profiles.objects.Profileable;
import com.mojang.authlib.GameProfile;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Represents an instruction that sets a property of a {@link GameProfile}.
 * It uses a {@link #profileContainer} to define how to set the property and
 * a {@link #profileable} to define what to set in the property.
 *
 * @param <T> The type of the result produced by the {@link #profileContainer} function.
 */
public final class ProfileInstruction<T> implements Profileable {
    /**
     * The function called that applies the given {@link #profileable} to an object that supports it
     * such as {@link ItemStack}, {@link SkullMeta} or a {@link BlockState}.
     */
    private final ProfileContainer<T> profileContainer;
    /**
     * The main profile to set.
     */
    private Profileable profileable;
    /**
     * All fallback profiles to try if the main one fails.
     */
    private final List<Profileable> fallbacks = new ArrayList<>();
    private Consumer<ProfileFallback<T>> onFallback;

    private boolean lenient = false;

    protected ProfileInstruction(ProfileContainer<T> profileContainer) {
        this.profileContainer = profileContainer;
    }

    /**
     * Removes the profile and skin texture from the item/block.
     */
    public T removeProfile() {
        profileContainer.setProfile(null);
        return profileContainer.getObject();
    }

    /**
     * Fails silently if any string based issues occur from a configuration standpoint.
     * Mainly affects {@link Profileable#detect(String)}
     */
    public ProfileInstruction<T> lenient() {
        this.lenient = true;
        return this;
    }

    /**
     * The current profile of the item/block (not the profile provided in {@link #profile(Profileable)})
     */
    @Override
    public GameProfile getProfile() {
        return profileContainer.getProfile();
    }

    /**
     * A string representation of the {@link #getProfile()} which is useful for data storage.
     */
    public String getProfileString() {
        return profileContainer.getProfileValue();
    }

    /**
     * Sets the texture profile to be set to the item/block. Use one of the
     * static methods of {@link Profileable} class.
     */
    public ProfileInstruction<T> profile(Profileable profileable) {
        this.profileable = profileable;
        return this;
    }

    /**
     * A list of fallback profiles in order. If the profile set in {@link #profile(Profileable)} fails,
     * these profiles will be tested in order until a correct one is found,
     * also if any of the fallback profiles are used, {@link #onFallback} will be called too.
     * @see #apply()
     */
    public ProfileInstruction<T> fallback(Profileable... fallbacks) {
        this.fallbacks.addAll(Arrays.asList(fallbacks));
        return this;
    }

    /**
     * Called when any of the {@link #fallback(Profileable...)} profiles are used,
     * this is also called if no fallback profile is provided, but the main one {@link #profile(Profileable)} fails.
     * @see #onFallback(Runnable)
     */
    public ProfileInstruction<T> onFallback(Consumer<ProfileFallback<T>> onFallback) {
        this.onFallback = onFallback;
        return this;
    }

    /**
     * @see #onFallback(Consumer)
     */
    public ProfileInstruction<T> onFallback(Runnable onFallback) {
        this.onFallback = (fallback) -> onFallback.run();
        return this;
    }

    /**
     * Sets the profile generated by the instruction to the result type synchronously.
     * This is recommended if your code is already not on the main thread, or if you know
     * that the skull texture doesn't need additional requests.
     *
     * <h2>What are these additional requests?</h2>
     * This only applies to offline mode (cracked) servers. Since these servers use
     * a cracked version of the player UUIDs and not their real ones, the real UUID
     * needs to be known by requesting it from Mojang servers and this request which
     * requires internet connection, will delay things a lot.
     *
     * @return The result after setting the generated profile.
     * @throws APIRetryException due to being ratelimited or network issues that can be fixed if the request is sent later again.
     * @throws MojangAPIException if any unknown non-recoverable network issues occur.
     * @throws UnknownPlayerException if a specific player-identifying {@link Profileable} is not found.
     * @throws InvalidProfileException if a given {@link Profileable} has incorrect value (more general than {@link UnknownPlayerException})
     * @throws ProfileChangeException all the exceptions above are added as a suppressed exception to this exception.
     */
    public T apply() {
        Objects.requireNonNull(profileable, "No profile was set");
        ProfileChangeException exception = null;

        List<Profileable> tries = new ArrayList<>(1 + fallbacks.size());
        tries.add(profileable);
        tries.addAll(fallbacks);
        if (lenient) tries.add(XSkull.getDefaultProfile());

        boolean success = false;
        boolean tryingFallbacks = false;
        for (Profileable profileable : tries) {
            try {
                GameProfile profile = profileable.getProfile();
                profileContainer.setProfile(profile);
                success = true;
                break;
            } catch (MojangAPIException | InvalidProfileException | APIRetryException ex) {
                if (exception == null) {
                    exception = new ProfileChangeException("Could not set the profile for " + profileContainer);
                }
                exception.addSuppressed(ex);
                tryingFallbacks = true;
            }
        }

        if (exception != null) {
            if (success || lenient) ProfilesCore.debug("apply()", exception);
            else throw exception;
        }

        T object = profileContainer.getObject();
        if (tryingFallbacks && this.onFallback != null) {
            ProfileFallback<T> fallback = new ProfileFallback<>(this, object, exception);
            this.onFallback.accept(fallback);
            object = fallback.getObject();
        }
        return object;
    }

    /**
     * Asynchronously applies the instruction to generate a {@link GameProfile} and returns a {@link CompletableFuture}.
     * This method is designed for non-blocking execution, allowing tasks to be performed
     * in the background without blocking the server's main thread.
     * This method will always execute async, even if the results are cached.
     * <p>
     * <h2>Reference Issues</h2>
     * Note that while these methods apply to the item/block instances, passing these instances
     * to certain methods, for example {@link org.bukkit.inventory.Inventory#setItem(int, ItemStack)}
     * will create a NMS copy of that instance and use that instead. Which means if for example
     * you're going to be using an item for an inventory, you'd have to set the item again
     * manually to the inventory once this method is done.
     * <pre>{@code
     * Inventory inventory = ...;
     * XSkull.createItem().profile(player).applyAsync()
     *     .thenAcceptAsync(item -> inventory.setItem(slot, item));
     * }</pre>
     *
     * <h2>Usage example:</h2>
     * <pre>{@code
     *   XSkull.createItem().profile(player).applyAsync()
     *      .thenAcceptAsync(result -> {
     *          // Additional processing...
     *      }, runnable -> Bukkit.getScheduler().runTask(plugin, runnable));
     * }</pre>
     *
     * @return A {@link CompletableFuture} that will complete asynchronously.
     */
    public CompletableFuture<T> applyAsync() {
        return CompletableFuture.supplyAsync(this::apply, PlayerProfileFetcherThread.EXECUTOR);
    }
}