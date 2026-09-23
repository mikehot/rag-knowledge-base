import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/network/providers.dart';
import '../../data/models/document_models.dart';
import '../../data/repositories/knowledge_repository.dart';

Future<void> showIndexTasksPanel(BuildContext context) async {
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (_) => const _IndexTasksPanel(),
  );
}

class _IndexTasksPanel extends ConsumerStatefulWidget {
  const _IndexTasksPanel();

  @override
  ConsumerState<_IndexTasksPanel> createState() => _IndexTasksPanelState();
}

class _IndexTasksPanelState extends ConsumerState<_IndexTasksPanel> {
  late Future<IndexTaskListResponse> _tasks;
  final Set<String> _retrying = {};

  KnowledgeRepository get _repository => ref.read(knowledgeRepositoryProvider);

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  void _refresh() => _tasks = _repository.listIndexTasks();

  Future<void> _retry(String taskId) async {
    setState(() => _retrying.add(taskId));
    try {
      await _repository.retryIndexTask(taskId);
      if (!mounted) return;
      setState(_refresh);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(error.toString())));
      }
    } finally {
      if (mounted) setState(() => _retrying.remove(taskId));
    }
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * 0.78,
        child: Column(
          children: [
            ListTile(
              title: const Text('索引任务'),
              subtitle: const Text('最近 20 条 · 仅知识库管理员可查看和重试'),
              trailing: IconButton(
                tooltip: '刷新',
                onPressed: () => setState(_refresh),
                icon: const Icon(Icons.refresh),
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: FutureBuilder<IndexTaskListResponse>(
                future: _tasks,
                builder: (context, snapshot) {
                  if (snapshot.connectionState != ConnectionState.done) {
                    return const Center(child: CircularProgressIndicator());
                  }
                  if (snapshot.hasError) {
                    return Center(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            snapshot.error.toString(),
                            textAlign: TextAlign.center,
                          ),
                          TextButton(
                            onPressed: () => setState(_refresh),
                            child: const Text('重试加载'),
                          ),
                        ],
                      ),
                    );
                  }
                  final items = snapshot.data?.items ?? const <IndexTaskItem>[];
                  if (items.isEmpty) return const Center(child: Text('暂无索引任务'));
                  return ListView.separated(
                    itemCount: items.length,
                    separatorBuilder: (_, _) => const Divider(height: 1),
                    itemBuilder: (context, index) {
                      final task = items[index];
                      final retrying = _retrying.contains(task.id);
                      return ListTile(
                        title: Text(
                          '${task.operation} · ${_status(task.status)}',
                        ),
                        subtitle: Text(
                          [
                            '文档 ${task.documentId}',
                            '尝试 ${task.attemptCount}/${task.maxAttempts}',
                            if (task.durationMs != null)
                              '耗时 ${task.durationMs} ms',
                            if (task.errorMessage?.isNotEmpty == true)
                              '失败：${task.errorMessage}',
                            if (task.createdAt != null)
                              DateFormat(
                                'MM-dd HH:mm',
                              ).format(task.createdAt!.toLocal()),
                          ].join(' · '),
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                        ),
                        trailing: task.status == 'FAILED'
                            ? IconButton(
                                tooltip: '安全重试',
                                onPressed: retrying
                                    ? null
                                    : () => _retry(task.id),
                                icon: retrying
                                    ? const SizedBox(
                                        width: 18,
                                        height: 18,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                        ),
                                      )
                                    : const Icon(Icons.replay),
                              )
                            : null,
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _status(String status) => switch (status) {
    'SUCCEEDED' => '成功',
    'FAILED' => '失败',
    'RUNNING' => '处理中',
    'RETRY_WAIT' => '等待重试',
    'PENDING' => '排队中',
    _ => status,
  };
}
