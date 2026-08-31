package dl.domain.ports;

import java.util.function.Supplier;

/**
 * "여기부터 여기까지가 한 단위" — 도메인만 아는 정보다.
 * 도메인은 묶음만 말하고 커밋도 롤백도 모른다.
 */
public interface UnitOfWork {
    <T> T within(Supplier<T> block);

    default void within(Runnable block) {
        within(() -> { block.run(); return null; });
    }
}
