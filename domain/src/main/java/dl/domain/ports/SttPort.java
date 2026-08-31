package dl.domain.ports;

import java.util.List;

/** seams.md ⓵ — 한도는 어댑터, 우선순위는 도메인. */
public interface SttPort {
    JobId requestTranscription(Audio audio, List<ContextItem> priorityOrdered);
    JobStatus jobStatus(JobId id);
    TranscriptionResult result(JobId id);

    record JobId(String value) {}
    record Audio(byte[] bytes, String filename) {}

    sealed interface JobStatus {
        record Waiting() implements JobStatus {}
        record Running(int doneChunks, int totalChunks) implements JobStatus {}
        record Done() implements JobStatus {}
        record Failed(String reason) implements JobStatus {}
    }

    enum ContextKind { ROSTER, TERM, OPEN_CANDIDATE, ISSUE }
    record ContextItem(ContextKind kind, String spelling, String meaning) {}

    /**
     * 회의록 한 줄.
     *
     * <p>{@code speakerLabel} 은 <b>optional 이다 — 화자 분리를 하지 않는 구현체는 null 을 준다.</b>
     * 필수로 두면 어댑터가 거짓 라벨을 채우고, 화면은 그걸 진짜 화자로 읽는다.
     * v0.5 는 화자 라벨의 소비자가 없다 (stt-requirements.md 요건 3).
     *
     * <p>타임스탬프는 구간으로 통일한다. 단어 단위를 주는 공급자면 어댑터가 묶는다 (seams.md ⓵).
     */
    record Utterance(String speakerLabel, double startSec, double endSec, String text) {}

    record TranscriptionResult(
        List<Utterance> transcript,
        List<ContextItem> appliedContext,
        List<ContextItem> droppedContext,
        List<String> rawResponses
    ) {}
}
