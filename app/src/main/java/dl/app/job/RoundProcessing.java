package dl.app.job;

import dl.domain.RoundOrchestrator;
import dl.domain.model.Model.MeetingId;
import dl.domain.ports.SttPort.Audio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 잡이 STT · 추출 경계를 넘는 자리. #3 이 세워 둔 {@link Processing} 자리에 가짜 대신 이게 꽂힌다.
 *
 * <p>도메인 어휘가 여기서 시작한다 — 잡 기계장치는 이 바깥에 남는다(#3 합의문 "거처").
 * 진행 보고 · 실패 · 재시도는 전부 {@link JobRunner} 의 일이고, 여기는 예외를 그대로 올린다.
 *
 * <p><b>전사와 추출 두 단계다.</b> 재시도가 같은 잡을 처음부터 다시 돌리므로 전사를 또 하면
 * 회의록이 덧붙고 이슈가 두 배로 뽑히는데, 그 건너뛰기는 오케스트레이터가 자기 안에서 한다 —
 * 재시도 · 시뮬레이터 · 애플리케이션이 같은 의미를 공짜로 얻는다.
 */
public final class RoundProcessing implements Processing {

    /** 전사와 추출. 진짜 공급자가 청크로 나눠 돌면 전사 쪽 구조는 #7 이 정한다. */
    private static final int STEPS = 2;

    private final RoundOrchestrator orchestrator;
    private final String promptVersion;

    public RoundProcessing(RoundOrchestrator orchestrator, String promptVersion) {
        this.orchestrator = orchestrator;
        this.promptVersion = promptVersion;
    }

    @Override
    public void process(UUID meeting, Path audio, ProgressReporter reporter) throws Exception {
        reporter.progress(0, STEPS);

        // 시뮬레이터가 꽂혀 있는 동안 이 바이트는 대본이다 (ADR 0005). 진짜 어댑터가 오면
        // 그대로 오디오가 된다 — 어느 쪽이든 포트가 받는 모양은 바이트 + 파일명이다.
        var payload = new Audio(Files.readAllBytes(audio), audio.getFileName().toString());
        var meetingId = new MeetingId(meeting.toString());
        orchestrator.transcribe(meetingId, payload);
        reporter.progress(1, STEPS);

        orchestrator.extract(meetingId, promptVersion);
        reporter.progress(STEPS, STEPS);
    }
}
