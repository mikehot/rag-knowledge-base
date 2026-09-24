import 'package:flutter_test/flutter_test.dart';
import 'package:rag_knowledge_base_app/features/documents/index_task_status.dart';

void main() {
  test('labels a pending task with a prior failure as waiting to retry', () {
    expect(
      indexTaskStatusLabel('PENDING', errorMessage: 'Embedding 调用失败，请检查模型服务配置'),
      '等待重试',
    );
  });

  test('keeps a new pending task labeled as queued', () {
    expect(indexTaskStatusLabel('PENDING'), '排队中');
  });

  test('does not treat a blank failure message as a retry', () {
    expect(indexTaskStatusLabel('PENDING', errorMessage: '  '), '排队中');
  });

  test('maps the remaining known task states', () {
    expect(indexTaskStatusLabel('RUNNING'), '处理中');
    expect(indexTaskStatusLabel('SUCCEEDED'), '成功');
    expect(indexTaskStatusLabel('FAILED'), '失败');
  });
}
