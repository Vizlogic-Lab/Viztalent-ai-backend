package com.smartstaff.util;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/** Thin wrapper around Tika so callers don't need to know about it directly. */
@Component
public class TextExtractor {

    private final Tika tika = new Tika();

    public String extract(InputStream in, String filename) throws IOException {
        try {
            return tika.parseToString(in);
        } catch (org.apache.tika.exception.TikaException e) {
            throw new IOException("Could not extract text from " + filename, e);
        }
    }
}
