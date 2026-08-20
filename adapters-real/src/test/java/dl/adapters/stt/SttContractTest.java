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

    private static List<ContextItem> 컨텍스트(int n) {
        var out = new ArrayList<ContextItem>();
        for (int i = 0; i < n; i++) out.add(new ContextItem(ContextKind.용어, "용어" + i + "0123456789", null));
        return out;
    }

    @Test
    void 전사요청은_결과를_낸다() {
        var port = adapter();
        var id = port.전사요청(new Audio("fake-audio".getBytes(), "m.mp3"), List.of());
        assertThat(port.작업상태(id)).isInstanceOf(JobStatus.Done.class);
        assertThat(port.결과(id).회의록()).isNotEmpty();
    }

    @Test
    void 컨텍스트는_조용히_사라지지_않는다() {
        var port = adapter();
        var 넣은것 = 컨텍스트(200);                       // 한도를 확실히 넘긴다
        var id = port.전사요청(new Audio("a".getBytes(), "m.mp3"), 넣은것);
        var r = port.결과(id);
        assertThat(r.반영된컨텍스트().size() + r.잘린컨텍스트().size()).isEqualTo(넣은것.size());
    }

    @Test
    void 원본응답을_보관한다() {
        var port = adapter();
        var id = port.전사요청(new Audio("a".getBytes(), "m.mp3"), List.of());
        assertThat(port.결과(id).원본응답()).isNotEmpty();
    }
}
