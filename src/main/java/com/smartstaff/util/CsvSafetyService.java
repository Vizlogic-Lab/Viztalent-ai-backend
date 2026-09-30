package com.smartstaff.util;

import org.springframework.stereotype.Service;

/**
 * Utilities for safely exporting CSV data to prevent formula injection attacks.
 * Prefixes cells starting with =, +, -, @ with a single quote to prevent them
 * from being interpreted as formulas by spreadsheet applications.
 */
@Service
public class CsvSafetyService {

    /**
     * Escape a CSV cell value to prevent formula injection.
     * If the value starts with =, +, -, or @, prefix it with a single quote.
     *
     * @param value The cell value to escape
     * @return The escaped value safe for CSV export
     */
    public String escapeCsvCell(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        char firstChar = value.charAt(0);
        if (firstChar == '=' || firstChar == '+' || firstChar == '-' || firstChar == '@') {
            return "'" + value;
        }

        return value;
    }

    /**
     * Escape a CSV row (array of cell values).
     *
     * @param row The row to escape
     * @return The escaped row
     */
    public String[] escapeCsvRow(String[] row) {
        if (row == null) {
            return row;
        }

        String[] escaped = new String[row.length];
        for (int i = 0; i < row.length; i++) {
            escaped[i] = escapeCsvCell(row[i]);
        }
        return escaped;
    }
}
