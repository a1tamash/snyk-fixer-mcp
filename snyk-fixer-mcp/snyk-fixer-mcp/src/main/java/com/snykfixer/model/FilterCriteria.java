package com.snykfixer.model;

import java.util.List;

public record FilterCriteria(
        List<String> severity,
        boolean fixableOnly,
        List<String> exploitMaturity
) {
    public FilterCriteria {
        if (severity == null) severity = List.of();
        if (exploitMaturity == null) exploitMaturity = List.of();
    }
}
