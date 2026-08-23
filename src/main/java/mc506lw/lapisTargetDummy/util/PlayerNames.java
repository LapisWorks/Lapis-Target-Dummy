package mc506lw.lapisTargetDummy.util;

/**
 * Validation for player names before they are sent to a profile lookup.
 * <p>
 * Shared by the name-tag path and {@code /ltd head set}, so junk input is
 * rejected once, in one place, instead of triggering a pointless network round
 * trip per caller.
 */
public final class PlayerNames {

    /** Mojang's own limit on player names. */
    public static final int MAX_LENGTH = 16;

    private PlayerNames() {
    }

    /**
     * @return {@code true} when the string could have been issued by Mojang:
     *         1–16 characters of {@code a-z}, {@code 0-9} or {@code _}
     */
    public static boolean isPlausible(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }
}
