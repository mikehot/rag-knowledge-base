import 'package:flutter_test/flutter_test.dart';
import 'package:rag_knowledge_base_app/data/models/ask_models.dart';

void main() {
  test('parses the backend answer contract including operations metadata', () {
    final answer = AskAnswer.fromJson({
      'answer': '设备整机保修 2 年。',
      'found': true,
      'grounded': true,
      'sources': [
        {
          'documentId': 'doc-1',
          'filename': 'sample_faq.md',
          'locator': '保修政策',
          'snippet': '整机保修 2 年。',
        },
      ],
      'requestId': 'request-1',
      'latencyMs': 1234,
      'tokenUsage': 88,
      'failureReason': null,
      'timings': {'embeddingMs': 100, 'retrievalMs': 34, 'generationMs': 1100},
    });

    expect(answer.requestId, 'request-1');
    expect(answer.latencyMs, 1234);
    expect(answer.tokenUsage, 88);
    expect(answer.timings?.retrievalMs, 34);
    expect(answer.timings?.generationMs, 1100);
    expect(answer.sources, hasLength(1));
  });

  test('serializes feedback using the backend enum values', () {
    expect(
      const AskFeedbackRequest(
        rating: 'NOT_HELPFUL',
        reason: '引用不够清晰',
      ).toJson(),
      {'rating': 'NOT_HELPFUL', 'reason': '引用不够清晰'},
    );
  });
}
