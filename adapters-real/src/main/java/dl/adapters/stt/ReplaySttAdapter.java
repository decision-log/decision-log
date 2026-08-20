package dl.adapters.stt;

import dl.domain.ports.SttPort;

import java.util.*;

/** 녹화된 공급자 응답을 진짜 어댑터의 normalize()에 그대로 먹인다 — 정규화 검증. */
public final class ReplaySttAdapter implements SttPort {

    private final String recordedResponse;
    private final OpenAiSttAdapter normalizer =
            new OpenAiSttAdapter("http://unused.invalid", "unused");
    private final Map<String, TranscriptionResult> done = new HashMap<>();

    public ReplaySttAdapter(String recordedResponse) { this.recordedResponse = recordedResponse; }

    @Override
    public JobId 전사요청(Audio audio, List<ContextItem> priorityOrdered) {
        var id = UUID.randomUUID().toString();
        done.put(id, normalizer.normalize(recordedResponse, priorityOrdered, List.of()));
        return new JobId(id);
    }

    @Override public JobStatus 작업상태(JobId id) { return new JobStatus.Done(); }
    @Override public TranscriptionResult 결과(JobId id) { return done.get(id.value()); }
}
