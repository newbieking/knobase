package com.knobase.business;

import java.util.ArrayList;
import java.util.List;

/** Deterministic Unicode-aware text segmentation, shared by text import and reindex. */
final class TextChunks {
    private TextChunks() {}

    static List<String> split(String text, int chunkSize) {
        if (chunkSize < 1) throw new IllegalArgumentException("chunkSize must be positive");
        int[] points = text.strip().codePoints().toArray();
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < points.length;) {
            int end = Math.min(start + chunkSize, points.length);
            if (end < points.length) {
                for (int i = end - 1; i >= start + chunkSize / 2; i--) {
                    if (points[i] == '\n' || points[i] == '。' || points[i] == '！' || points[i] == '？' || points[i] == ';') {
                        end = i + 1;
                        break;
                    }
                }
            }
            String chunk = new String(points, start, end - start).strip();
            if (!chunk.isBlank()) chunks.add(chunk);
            start = end;
        }
        return chunks;
    }
}
