package com.cryptomorin.xseries.profiles;

import com.cryptomorin.xseries.profiles.skull.XSkull;
import com.mojang.authlib.GameProfile;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code SkullInputType} enum represents different types of input patterns that can be used for identifying
 * and validating various formats such as texture hashes, URLs, Base64 encoded strings, UUIDs, and usernames.
 */
@SuppressWarnings("JavadocLinkAsPlainText")
public enum PlayerTextureInputType {
    /**
     * Represents a texture hash pattern.
     * Mojang hashes length are inconsistent, and they don't seem to use uppercase characters.
     * <p>
     * Currently, the shortest observed hash value is (lenght: 57): 0a4050e7aacc4539202658fdc339dd182d7e322f9fbcc4d5f99b5718a
     * <p>
     * Example: e5461a215b325fbdf892db67b7bfb60ad2bf1580dc968a15dfb304ccd5e74db
     */
    TEXTURE_HASH(Pattern.compile("[0-9a-z]{55,70}")) {
        @Override
        public GameProfile getProfile(String textureHash) {
            String base64 = PlayerProfiles.encodeBase64(PlayerProfiles.TEXTURES_NBT_PROPERTY_PREFIX + PlayerProfiles.TEXTURES_BASE_URL + textureHash + "\"}}}");
            return PlayerProfiles.profileFromHashAndBase64(textureHash, base64);
        }
    },

    /**
     * Represents a texture URL pattern that includes the base URL followed by the texture hash pattern.
     * <p>
     * Example: http://textures.minecraft.net/texture/e5461a215b325fbdf892db67b7bfb60ad2bf1580dc968a15dfb304ccd5e74db
     */
    TEXTURE_URL(Pattern.compile(Pattern.quote(PlayerProfiles.TEXTURES_BASE_URL) + "(?<hash>" + TEXTURE_HASH.pattern + ')')) {
        @Override
        public GameProfile getProfile(String textureUrl) {
            String hash = extractTextureHash(textureUrl);
            return TEXTURE_HASH.getProfile(hash);
        }
    },

    /**
     * Represents a Base64 encoded string pattern.
     * The base64 pattern that's checked is not a general base64 pattern, but a pattern that
     * closely represents the base64 genereated by the NBT data.
     */
    BASE64(Pattern.compile("[-A-Za-z0-9+/]{100,}={0,3}")) {
        @Override
        public GameProfile getProfile(String base64) {
            return Optional.ofNullable(PlayerProfiles.decodeBase64(base64))
                    .map(PlayerTextureInputType::extractTextureHash)
                    .map((hash) -> PlayerProfiles.profileFromHashAndBase64(hash, base64))
                    .orElseGet(XSkull::getDefaultProfile);
        }
    },

    /**
     * Represents a UUID pattern, following the standard UUID format.
     */
    UUID(Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", Pattern.CASE_INSENSITIVE)) {
        @Override
        public GameProfile getProfile(String uuidString) {
            return Profileable.of(java.util.UUID.fromString(uuidString)).getProfile();
        }
    },

    /**
     * Represents a username pattern, allowing alphanumeric characters and underscores, with a length of 1 to 16 characters.
     * Minecraft now requires the username to be at least 3 characters long, but older accounts are still around.
     * It also seems that there are a few inactive accounts that use spaces in their usernames?
     */
    USERNAME(Pattern.compile("[A-Za-z0-9_]{1,16}")) {
        @Override
        public GameProfile getProfile(String username) {
            return Profileable.username(username).getProfile();
        }
    };

    /**
     * The regex pattern associated with the input type.
     */
    private final Pattern pattern;
    private static final PlayerTextureInputType[] VALUES = values();

    /**
     * Constructs a {@code SkullInputType} with the specified regex pattern.
     *
     * @param pattern The regex pattern associated with the input type.
     */
    PlayerTextureInputType(Pattern pattern) {
        this.pattern = pattern;
    }

    /**
     * Retrieves a {@link GameProfile} based on the provided input string.
     *
     * @param input The input string to retrieve the profile for.
     * @return The {@link GameProfile} corresponding to the input string.
     */
    public abstract GameProfile getProfile(String input);

    /**
     * Returns the corresponding {@code SkullInputType} for the given identifier, if it matches any pattern.
     *
     * @param identifier The string to be checked against the patterns.
     * @return The matching {@code InputType}, or {@code null} if no match is found.
     */
    @Nullable
    public static PlayerTextureInputType get(@Nonnull String identifier) {
        Objects.requireNonNull(identifier, "Identifier cannot be null");
        return Arrays.stream(VALUES)
                .filter(value -> value.pattern.matcher(identifier).matches())
                .findFirst().orElse(null);
    }

    /**
     * Extracts the texture hash from the provided input string.
     * <p>
     * Will not work reliably if NBT is passed: {"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/74133f6ac3be2e2499a784efadcfffeb9ace025c3646ada67f3414e5ef3394"}}}
     *
     * @param input The input string containing the texture hash.
     * @return The extracted texture hash.
     */
    private static String extractTextureHash(String input) {
        Matcher matcher = PlayerTextureInputType.TEXTURE_HASH.pattern.matcher(input);
        return matcher.find() ? matcher.group() : null;
    }
}