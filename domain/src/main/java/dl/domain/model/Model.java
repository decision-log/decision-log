package dl.domain.model;

import java.util.List;

public final class Model {
    public record MeetingId(String value) {}
    public record IssueId(String value) {}

    public enum State { 쟁점, 결정, 실행됨, 무효 }

    /** 아직 이슈가 아니다 — 추적 대상이 아니므로 따로 산다 (seams.md) */
    public record IssueCandidate(IssueId id, MeetingId meeting, String question, State state, String answer, List<Integer> evidenceSpans) {}

    public record Issue(IssueId id, String question, State state, String answer) {}

    public record Term(String spelling, String meaning) {}

    public record TermCandidate(String spelling, String meaning, MeetingId meeting) {}
}
