package dl.domain.ports;

import dl.domain.model.Model.*;
import java.util.List;

public interface ExtractPort {
    ExtractionResult extract(List<SttPort.Utterance> transcript, String promptVersion);

    record ExtractionResult(List<IssueCandidateDraft> issueCandidates, List<TermCandidate> termCandidates, String modelName, String promptHash) {}
    record IssueCandidateDraft(String question, State state, String answer, List<Integer> evidenceSpans) {}
}
