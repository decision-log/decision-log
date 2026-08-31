package dl.adapters.stt;

import dl.domain.ports.SttPort;
import dl.domain.ports.SttPort.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 어댑터 세 벌이 전부 통과해야 하는 계약. Spring 없음. */
abstract class SttContractTest {

    abstract SttPort adapter();

    private static List<ContextItem> context(int n) {
        var out = new ArrayList<ContextItem>();
        for (int i = 0; i < n; i++) out.add(new ContextItem(ContextKind.TERM, "용어" + i + "0123456789", null));
        return out;
    }

    @Test
    void 전사요청은_결과를_낸다() {
        var port = adapter();
        var id = port.requestTranscription(new Audio("fake-audio".getBytes(), "m.mp3"), List.of());
        assertThat(port.jobStatus(id)).isInstanceOf(JobStatus.Done.class);
        assertThat(port.result(id).transcript()).isNotEmpty();
    }

    @Test
    void 컨텍스트는_조용히_사라지지_않는다() {
        var port = adapter();
        var sent = context(200);                       // 한도를 확실히 넘긴다
        var id = port.requestTranscription(new Audio("a".getBytes(), "m.mp3"), sent);
        var r = port.result(id);
        assertThat(r.appliedContext().size() + r.droppedContext().size()).isEqualTo(sent.size());
    }

    @Test
    void 원본응답을_보관한다() {
        var port = adapter();
        var id = port.requestTranscription(new Audio("a".getBytes(), "m.mp3"), List.of());
        assertThat(port.result(id).rawResponses()).isNotEmpty();
    }

    /**
     * 화자 라벨은 optional 이다. 화자 분리를 하지 않는 구현체는 null 을 주고,
     * {@code "화자?"} 같은 자리 채우기를 하지 않는다 — 화면이 그걸 진짜 화자로 읽는다.
     */
    @Test
    void 화자_라벨을_거짓으로_채우지_않는다() {
        var port = adapter();
        var id = port.requestTranscription(new Audio("a".getBytes(), "m.mp3"), List.of());
        for (var u : port.result(id).transcript())
            assertThat(u.speakerLabel() == null || !u.speakerLabel().isBlank())
                    .as("화자 라벨: '%s'", u.speakerLabel())
                    .isTrue();
    }

    /** 타임스탬프는 구간으로 통일한다 (seams.md ⓵). 근거가 회의록의 한 지점을 가리켜야 한다. */
    @Test
    void 타임스탬프는_구간이다() {
        var port = adapter();
        var id = port.requestTranscription(new Audio("a\nb\nc".getBytes(), "m.mp3"), List.of());
        var transcript = port.result(id).transcript();

        double previousStart = Double.NEGATIVE_INFINITY;
        for (var u : transcript) {
            assertThat(u.startSec()).as("시작 ≤ 끝").isLessThanOrEqualTo(u.endSec());
            assertThat(u.startSec()).as("구간은 앞으로만 간다").isGreaterThanOrEqualTo(previousStart);
            previousStart = u.startSec();
        }
    }
}
