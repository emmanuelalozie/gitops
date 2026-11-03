package gitops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * When a target may be applied, for systems that should only change in a maintenance window. Configured as
 * "applyWindow" in target.json: {@code days} (such as ["sat", "sun"]; every day when left out), {@code start} and
 * {@code end} ("02:00" to "05:00"; a window may run past midnight) and {@code timezone} (default UTC). Applies
 * with --respect-window outside it are deferred; the Apply workflow's hourly run applies them once it opens.
 */
public record ApplyWindow(Set<DayOfWeek> days, LocalTime start, LocalTime end, ZoneId zone) {

    /** The target's window, or null when it can be applied any time. */
    public static ApplyWindow of(Target target) {
        JsonNode spec = target.settings().get("applyWindow");
        if (spec == null || spec.isNull()) {
            return null;
        }
        String where = target.dir().resolve(Target.FILE) + ": \"applyWindow\"";
        try {
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            for (JsonNode day : spec.path("days")) {
                days.add(day(day.asText()));
            }
            if (days.isEmpty()) {
                days = EnumSet.allOf(DayOfWeek.class);
            }
            LocalTime start = LocalTime.parse(spec.path("start").asText());
            LocalTime end = LocalTime.parse(spec.path("end").asText());
            if (start.equals(end)) {
                throw new IllegalArgumentException("start and end are the same time");
            }
            return new ApplyWindow(days, start, end, ZoneId.of(spec.path("timezone").asText("UTC")));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(where + " needs \"start\" and \"end\" as HH:MM, such as \"02:00\"");
        } catch (java.time.DateTimeException | IllegalArgumentException e) {
            throw new IllegalArgumentException(where + ": " + e.getMessage());
        }
    }

    private static DayOfWeek day(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        for (DayOfWeek d : DayOfWeek.values()) {
            String name = d.name().toLowerCase(Locale.ROOT);
            if (t.length() >= 3 && name.startsWith(t)) {
                return d;
            }
        }
        throw new IllegalArgumentException("unknown day '" + text + "' (use mon, tue, ...)");
    }

    /** Whether the window is open at this moment. A window past midnight belongs to the day it starts. */
    public boolean isOpen(Instant at) {
        ZonedDateTime now = at.atZone(zone);
        LocalTime time = now.toLocalTime();
        if (start.isBefore(end)) {
            return days.contains(now.getDayOfWeek()) && !time.isBefore(start) && time.isBefore(end);
        }
        // Runs past midnight: open from start on a listed day, or before end on the day after one.
        return (days.contains(now.getDayOfWeek()) && !time.isBefore(start))
                || (days.contains(now.getDayOfWeek().minus(1)) && time.isBefore(end));
    }

    /** The next time the window opens (now, if it's open). */
    public Instant nextOpening(Instant from) {
        if (isOpen(from)) {
            return from;
        }
        ZonedDateTime candidate = from.atZone(zone).truncatedTo(ChronoUnit.DAYS).with(start);
        for (int i = 0; i < 9; i++) {
            ZonedDateTime day = candidate.plusDays(i);
            if (days.contains(day.getDayOfWeek()) && day.toInstant().isAfter(from)) {
                return day.toInstant();
            }
        }
        throw new IllegalStateException("No opening within a week");
    }

    public String describe() {
        String dayText = days.size() == 7 ? "every day"
                : String.join(", ", days.stream().map(d -> d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)).toList());
        return dayText + " " + start + "–" + end + " " + zone.getId();
    }

    public ObjectNode toJson(Instant now) {
        return Json.MAPPER.createObjectNode().put("describe", describe()).put("open", isOpen(now))
                .put("nextOpening", nextOpening(now).toString());
    }
}
