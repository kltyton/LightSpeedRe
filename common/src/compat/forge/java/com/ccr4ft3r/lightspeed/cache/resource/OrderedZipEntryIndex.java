package com.ccr4ft3r.lightspeed.cache.resource;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Selects ZIP entries without changing their central-directory order. */
public final class OrderedZipEntryIndex {
    private static final String NATIVE_ITERATOR = "java.util.zip.ZipFile$ZipEntryIterator";

    private final ZipFile zip;
    private final OrderedResourcePathIndex paths;
    private final boolean duplicateNames;

    private OrderedZipEntryIndex(ZipFile zip, OrderedResourcePathIndex paths, boolean duplicateNames) {
        this.zip = zip;
        this.paths = paths;
        this.duplicateNames = duplicateNames;
    }

    public static OrderedZipEntryIndex create(ZipFile zip) {
        List<String> names = new ArrayList<>(zip.size());
        Set<String> unique = new HashSet<>();
        boolean duplicates = false;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            names.add(name);
            if (!unique.add(name)) duplicates = true;
        }
        return new OrderedZipEntryIndex(zip, duplicates ? null : OrderedResourcePathIndex.from(names), duplicates);
    }

    public boolean matches(ZipFile candidate) {
        return zip == candidate;
    }

    public Boolean contains(String name) {
        return duplicateNames ? null : paths.contains(name);
    }

    public Boolean hasAnyUnder(String path) {
        return duplicateNames ? null : paths.hasAtOrUnder(path);
    }

    public static boolean supports(ZipFile zip, Enumeration<? extends ZipEntry> original) {
        return zip.getClass() == ZipFile.class && original != null
                && original.getClass().getName().equals(NATIVE_ITERATOR);
    }

    public Enumeration<? extends ZipEntry> select(String prefix, Enumeration<? extends ZipEntry> original) {
        if (duplicateNames || !supports(zip, original)) {
            return original;
        }
        List<String> selected = paths.entriesStartingWith(prefix);
        return new Enumeration<>() {
            private int index;

            @Override
            public boolean hasMoreElements() {
                return index < selected.size();
            }

            @Override
            public ZipEntry nextElement() {
                if (!hasMoreElements()) {
                    zip.size();
                    throw new NoSuchElementException();
                }
                return zip.getEntry(selected.get(index++));
            }
        };
    }
}
