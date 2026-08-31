package dl.adapters.stt;

import dl.adapters.stt.SimulatorSttAdapter.Mode;
import dl.adapters.stt.SimulatorSttAdapter.Rule;

import java.util.List;

/**
 * 팀이 도구 없이 치른 회의 4회차분 전사본에서 실제로 센 오염이다.
 *
 * <p><b>여기에 상상으로 지어낸 규칙을 넣지 않는다.</b> 넣는 순간 시뮬레이터가 검증하는 것이
 * 설계가 아니라 우리 상상이 된다 ([ADR 0005] "오염 규칙은 실측에서만 채운다").
 * 기제를 보여주려고 만든 규칙이 필요하면 쓰는 쪽에서 자기 손으로 만든다.
 *
 * <p>실측은 <b>정방향만</b> 줬다 — 컨텍스트를 넣지 않은 회차의 전사본이라
 * 주입한 어휘로 끌려간 자리를 셀 수 없었다. 그래서 끌려오는말이 비어 있다.
 * 그 빈도는 공급자 평가 절차 3번이 답한다 (stt-requirements.md).
 */
public final class MeasuredCorruptionRules {

    /** 6회 전부 같은 형태로 깨졌다. */
    public static final Rule SCRUM = new Rule("스크럼", Mode.STEADY, List.of("시끄러움"));

    /** 8회 전부 같은 형태. */
    public static final Rule GIT = new Rule(".git", Mode.STEADY, List.of("점기"));

    /** 6회 전부 같은 형태. {@link #깃} 과 접두사가 겹친다 — 어댑터가 긴 정답부터 적용한다. */
    public static final Rule GITIGNORE = new Rule(".gitignore", Mode.STEADY, List.of("GD 근원"));

    /** 매번 다르게 깨져 같은 말인지도 모른다. 용어 후보의 "반복" 신호에도 안 걸린다. */
    public static final Rule TOOL_CALLING =
            new Rule("툴 콜링", Mode.WOBBLY, List.of("툴 풀링", "풀 콜링", "툴 콜딩"));

    public static List<Rule> all() {
        return List.of(SCRUM, GIT, GITIGNORE, TOOL_CALLING);
    }

    private MeasuredCorruptionRules() {}
}
