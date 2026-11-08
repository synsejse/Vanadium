package com.synsenetwork.vanadium.concurrent;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Key encoding for {@code ConcurrentHashMap<Long, ?>} backings of packed positions. Packed chunk keys
 * hash to {@code x ^ z} under {@code Long.hashCode}, so a 32x32 chunk area collapses onto 32 hashes and
 * the map degrades to tree bins. Stored keys go through murmur3's bijective 64-bit finalizer; views
 * invert it. (fastutil's {@code HashCommon.mix} is unsuitable here: its xor-folding cancels with
 * {@code Long.hashCode} and the map's own spreading, which clusters packed section keys.)
 */
final class MixedLongKeys {
    private static final long C1 = 0xff51afd7ed558ccdL;
    private static final long C2 = 0xc4ceb9fe1a85ec53L;
    private static final long C1_INVERSE = inverse(C1);
    private static final long C2_INVERSE = inverse(C2);

    private MixedLongKeys() {}

    static long mix(long key) {
        key ^= key >>> 33;
        key *= C1;
        key ^= key >>> 33;
        key *= C2;
        return key ^ key >>> 33;
    }

    /** Each step inverts itself (xor-shift by at least 32) or multiplies by the modular inverse. */
    static long unmix(long key) {
        key ^= key >>> 33;
        key *= C2_INVERSE;
        key ^= key >>> 33;
        key *= C1_INVERSE;
        return key ^ key >>> 33;
    }

    /** Multiplicative inverse modulo 2^64 of an odd constant (Newton iteration). */
    private static long inverse(long odd) {
        long x = odd;
        for (int i = 0; i < 6; i++) x *= 2 - odd * x;
        return x;
    }

    /** A live view of a mixed-key backing map that exposes and accepts original keys. */
    static <V> Map<Long, V> unmixedView(Map<Long, V> backing) {
        return new UnmixedView<>(backing);
    }

    /** Original-key view over the mixed backing map; lookups mix, iteration unmixes. */
    private static final class UnmixedView<V> extends AbstractMap<Long, V> {
        private final Map<Long, V> backing;

        UnmixedView(Map<Long, V> backing) {
            this.backing = backing;
        }

        @Override public V get(Object key) {
            return key instanceof Long k ? backing.get(mix(k)) : null;
        }

        @Override public boolean containsKey(Object key) {
            return key instanceof Long k && backing.containsKey(mix(k));
        }

        @Override public V put(Long key, V value) {
            return backing.put(mix(key), value);
        }

        @Override public V remove(Object key) {
            return key instanceof Long k ? backing.remove(mix(k)) : null;
        }

        @Override public int size() {
            return backing.size();
        }

        @Override public void clear() {
            backing.clear();
        }

        @Override public Set<Long> keySet() {
            return new AbstractSet<>() {
                @Override public Iterator<Long> iterator() {
                    Iterator<Long> keys = backing.keySet().iterator();
                    return new Iterator<>() {
                        public boolean hasNext() { return keys.hasNext(); }
                        public Long next() { return unmix(keys.next()); }
                        public void remove() { keys.remove(); }
                    };
                }
                @Override public int size() { return backing.size(); }
                @Override public boolean contains(Object o) { return containsKey(o); }
                @Override public boolean remove(Object o) {
                    return o instanceof Long k && backing.keySet().remove(mix(k));
                }
                @Override public void clear() { backing.clear(); }
            };
        }

        @Override public Set<Entry<Long, V>> entrySet() {
            return new AbstractSet<>() {
                @Override public Iterator<Entry<Long, V>> iterator() {
                    Iterator<Entry<Long, V>> entries = backing.entrySet().iterator();
                    return new Iterator<>() {
                        public boolean hasNext() { return entries.hasNext(); }
                        public Entry<Long, V> next() {
                            Entry<Long, V> entry = entries.next();
                            long key = unmix(entry.getKey());
                            return new SimpleEntry<>(key, entry.getValue()) {
                                @Override public V setValue(V value) {
                                    super.setValue(value);
                                    return entry.setValue(value);
                                }
                            };
                        }
                        public void remove() { entries.remove(); }
                    };
                }
                @Override public int size() { return backing.size(); }
                @Override public boolean contains(Object o) {
                    return o instanceof Entry<?, ?> e && e.getKey() instanceof Long k
                            && Objects.equals(backing.get(mix(k)), e.getValue()) && backing.containsKey(mix(k));
                }
                @Override public boolean remove(Object o) {
                    return o instanceof Entry<?, ?> e && e.getKey() instanceof Long k && backing.remove(mix(k), e.getValue());
                }
                @Override public void clear() { backing.clear(); }
            };
        }
    }

}
