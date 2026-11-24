package io.github.madaai.idlestow.core.model;

import io.github.madaai.idlestow.core.model.Enums.ActivityClass;
import io.github.madaai.idlestow.core.model.Enums.Confidence;

import java.util.List;

/**
 * Result of the deterministic activity rules for one file, including a human readable
 * explanation of every piece of evidence used.
 *
 * @param lastActivityMs most recent trustworthy activity, or 0 when unknown
 * @param idleDays       days since {@code lastActivityMs}, or -1 when unknown
 * @param reasons        individual evidence lines, most important first
 */
public record ActivityAssessment(
        ActivityClass classification,
        Confidence confidence,
        long lastActivityMs,
        long idleDays,
        List<String> reasons) {

    public String explanation() {
        return String.join(" ", reasons);
    }
}
