package dl.domain.model;

import java.util.List;

public final class Model {
    public record 회의ID(String value) {}
    public record 이슈ID(String value) {}

    public enum 상태 { 쟁점, 결정, 실행됨, 무효 }

    /** 아직 이슈가 아니다 — 추적 대상이 아니므로 따로 산다 (seams.md) */
    public record 이슈후보(이슈ID id, 회의ID 회의, String 질문, 상태 상태, String 답, List<Integer> 근거구간) {}

    public record 이슈(이슈ID id, String 질문, 상태 상태, String 답) {}

    public record 용어(String 표기, String 뜻) {}

    public record 용어후보(String 표기, String 뜻, 회의ID 회의) {}
}
