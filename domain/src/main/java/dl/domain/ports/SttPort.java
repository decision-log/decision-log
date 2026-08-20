package dl.domain.ports;

import java.util.List;

/** seams.md ⓵ — 한도는 어댑터, 우선순위는 도메인. */
public interface SttPort {
    JobId 전사요청(Audio audio, List<ContextItem> priorityOrdered);
    JobStatus 작업상태(JobId id);
    TranscriptionResult 결과(JobId id);

    record JobId(String value) {}
    record Audio(byte[] bytes, String filename) {}

    sealed interface JobStatus {
        record Waiting() implements JobStatus {}
        record Running(int doneChunks, int totalChunks) implements JobStatus {}
        record Done() implements JobStatus {}
        record Failed(String reason) implements JobStatus {}
    }

    enum ContextKind { 명단, 용어, 후보쟁점, 이슈 }
    record ContextItem(ContextKind kind, String 표기, String 뜻) {}
    record Utterance(String speakerLabel, double startSec, double endSec, String text) {}

    record TranscriptionResult(
        List<Utterance> 회의록,
        List<ContextItem> 반영된컨텍스트,
        List<ContextItem> 잘린컨텍스트,
        List<String> 원본응답
    ) {}
}
