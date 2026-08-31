package dl.app.job;

import dl.domain.RoundOrchestrator;
import dl.domain.model.Model.MeetingId;
import dl.domain.ports.SttPort.Audio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 잡이 STT 경계를 넘는 자리. #3 이 세워 둔 {@link 처리} 자리에 가짜 대신 이게 꽂힌다.
 *
 * <p>도메인 어휘가 여기서 시작한다 — 잡 기계장치는 이 바깥에 남는다(#3 합의문 "거처").
 * 진행 보고 · 실패 · 재시도는 전부 {@link 잡실행기} 의 일이고, 여기는 예외를 그대로 올린다.
 *
 * <p><b>지금은 전사까지다.</b> 추출 경계는 #5 의 산출물이라, #5 가 여기에
 * {@code 추출한다} 를 덧붙이고 진행을 2단계로 늘린다.
 */
public final class RoundProcessing implements Processing {

    /** 전사 하나뿐이다. 진짜 공급자가 청크로 나눠 돌면 그 구조는 #7 이 정한다. */
    private static final int steps = 1;

    private final RoundOrchestrator orchestrator;

    public RoundProcessing(RoundOrchestrator orchestrator) { this.orchestrator = orchestrator; }

    @Override
    public void process(UUID meeting, Path audio, ProgressReporter reporter) throws Exception {
        reporter.progress(0, steps);

        // 시뮬레이터가 꽂혀 있는 동안 이 바이트는 대본이다 (ADR 0005). 진짜 어댑터가 오면
        // 그대로 오디오가 된다 — 어느 쪽이든 포트가 받는 모양은 바이트 + 파일명이다.
        var payload = new Audio(Files.readAllBytes(audio), audio.getFileName().toString());
        orchestrator.transcribe(new MeetingId(meeting.toString()), payload);

        reporter.progress(steps, steps);
    }
}
