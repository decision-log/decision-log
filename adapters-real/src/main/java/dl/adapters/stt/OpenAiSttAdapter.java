package dl.adapters.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dl.domain.ports.SttPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** gpt-4o-transcribe. Spring RestClient 사용 (안 b). */
public final class OpenAiSttAdapter implements SttPort {

    private static final int PROMPT_CHAR_BUDGET = 600;

    private final RestClient client;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, TranscriptionResult> done = new ConcurrentHashMap<>();

    public OpenAiSttAdapter(String baseUrl, String apiKey) {
        this.client = RestClient.builder().baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey).build();
    }

    @Override
    public JobId requestTranscription(Audio audio, List<ContextItem> priorityOrdered) {
        var kept = new ArrayList<ContextItem>();
        var dropped = new ArrayList<ContextItem>();
        int used = 0;
        for (var item : priorityOrdered) {
            var r = render(item);
            if (used + r.length() <= PROMPT_CHAR_BUDGET) { kept.add(item); used += r.length(); }
            else dropped.add(item);
        }

        var body = new MultipartBodyBuilder();
        body.part("model", "gpt-4o-transcribe");
        body.part("response_format", "json");
        body.part("prompt", String.join(", ", kept.stream().map(this::render).toList()));
        body.part("file", new ByteArrayResource(audio.bytes()) {
            @Override public String getFilename() { return audio.filename(); }
        });

        String raw = null;
        RuntimeException last = null;
        for (int i = 0; i < 3; i++) {                       // 재시도는 여전히 손으로
            try {
                raw = client.post().uri("/v1/audio/transcriptions")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body.build()).retrieve().body(String.class);
                last = null; break;
            } catch (RuntimeException e) {
                last = e;
                try { Thread.sleep(200L << i); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException(ie); }
            }
        }
        if (last != null) throw last;

        var id = UUID.randomUUID().toString();
        done.put(id, normalize(raw, kept, dropped));
        return new JobId(id);
    }

    private String render(ContextItem i) { return i.meaning() == null ? i.spelling() : i.spelling() + "(" + i.meaning() + ")"; }

    TranscriptionResult normalize(String raw, List<ContextItem> kept, List<ContextItem> dropped) {
        try {
            JsonNode root = json.readTree(raw);
            var us = new ArrayList<Utterance>();
            JsonNode segs = root.path("segments");
            if (segs.isArray() && !segs.isEmpty())
                for (JsonNode s : segs)
                    us.add(new Utterance(null, s.path("start").asDouble(), s.path("end").asDouble(), s.path("text").asText().trim()));
            else us.add(new Utterance(null, 0d, 0d, root.path("text").asText().trim()));
            return new TranscriptionResult(us, kept, dropped, List.of(raw));
        } catch (Exception e) { throw new IllegalStateException("normalize failed", e); }
    }

    @Override public JobStatus jobStatus(JobId id) { return new JobStatus.Done(); }
    @Override public TranscriptionResult result(JobId id) { return done.get(id.value()); }
}
