package dl.domain.ports;

import java.util.function.Supplier;

/**
 * "여기부터 여기까지가 한 단위" — 도메인만 아는 정보다.
 * 도메인은 묶음만 말하고 커밋도 롤백도 모른다.
 */
public interface 단위작업 {
    <T> T 안에서(Supplier<T> 묶음);

    default void 안에서(Runnable 묶음) {
        안에서(() -> { 묶음.run(); return null; });
    }
}
