package com.protogemcouch.subscription;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * A client's registered interest within a region — the predicate that decides whether a mutation's key
 * should be pushed to that client's feed. Geode register-interest supports interest in all keys, a
 * specific key or key list, or a regular expression; a client may register several, so a region's
 * interest is the union of its {@code Interest}s (see {@link SubscriptionRegistry}).
 */
public sealed interface Interest {

    /** True if a mutation on {@code key} matches this interest. */
    boolean matches(String key);

    static Interest allKeys() {
        return AllKeys.INSTANCE;
    }

    static Interest keys(Set<String> keys) {
        return new Keys(Set.copyOf(keys));
    }

    static Interest regex(String regex) {
        return new Regex(Pattern.compile(regex));
    }

    /** Every key in the region (Geode {@code "ALL_KEYS"} / regex {@code ".*"}). */
    record AllKeys() implements Interest {
        static final AllKeys INSTANCE = new AllKeys();

        @Override
        public boolean matches(String key) {
            return true;
        }
    }

    /** A fixed set of keys (a single-key register, or REGISTER_INTEREST_LIST). */
    record Keys(Set<String> keys) implements Interest {
        @Override
        public boolean matches(String key) {
            return key != null && keys.contains(key);
        }
    }

    /** A regular expression matched against the whole key (Geode {@code registerInterestRegex}). */
    record Regex(Pattern pattern) implements Interest {
        @Override
        public boolean matches(String key) {
            return key != null && pattern.matcher(key).matches();
        }
    }
}
