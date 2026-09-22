import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/widgets/state_views.dart';
import '../../data/models/document_models.dart';
import '../auth/auth_view_model.dart';
import 'documents_view_model.dart';

class DocumentsPage extends ConsumerStatefulWidget {
  const DocumentsPage({super.key});

  @override
  ConsumerState<DocumentsPage> createState() => _DocumentsPageState();
}

class _DocumentsPageState extends ConsumerState<DocumentsPage> {
  @override
  void initState() {
    super.initState();
    Future.microtask(
      () => ref.read(documentsViewModelProvider.notifier).load(),
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(documentsViewModelProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('知识库'),
        actions: [
          TextButton.icon(
            onPressed: state.uploading
                ? null
                : () => ref
                      .read(documentsViewModelProvider.notifier)
                      .pickAndUpload(),
            icon: state.uploading
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.upload_file),
            label: const Text('上传文档'),
          ),
          IconButton(
            onPressed: () => ref.read(authViewModelProvider.notifier).logout(),
            icon: const Icon(Icons.logout),
            tooltip: '退出登录',
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: Builder(
        builder: (context) {
          if (state.loading && state.items.isEmpty) {
            return const LoadingView();
          }
          if (state.error != null && state.items.isEmpty) {
            return ErrorRetryView(
              message: state.error!,
              onRetry: () =>
                  ref.read(documentsViewModelProvider.notifier).load(),
            );
          }
          if (state.items.isEmpty) {
            return EmptyActionView(
              message: '还没有文档，点右上角上传',
              actionLabel: '上传文档',
              onAction: () =>
                  ref.read(documentsViewModelProvider.notifier).pickAndUpload(),
            );
          }
          return RefreshIndicator(
            onRefresh: () =>
                ref.read(documentsViewModelProvider.notifier).load(),
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemBuilder: (context, index) => _DocumentCard(
                item: state.items[index],
                busy:
                    state.operatingDocumentId == state.items[index].documentId,
                onDelete: () => ref
                    .read(documentsViewModelProvider.notifier)
                    .delete(state.items[index].documentId),
                onDisable: () => _confirmDisable(state.items[index]),
                onReindex: () => ref
                    .read(documentsViewModelProvider.notifier)
                    .reindex(state.items[index].documentId),
              ),
              separatorBuilder: (context, index) => const SizedBox(height: 12),
              itemCount: state.items.length,
            ),
          );
        },
      ),
    );
  }

  Future<void> _confirmDisable(DocumentItem item) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('停用文档？'),
        content: Text('停用后“${item.filename}”不会继续参与检索。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('停用'),
          ),
        ],
      ),
    );
    if (confirmed == true && mounted) {
      await ref
          .read(documentsViewModelProvider.notifier)
          .disable(item.documentId);
    }
  }
}

class _DocumentCard extends StatelessWidget {
  const _DocumentCard({
    required this.item,
    required this.busy,
    required this.onDelete,
    required this.onDisable,
    required this.onReindex,
  });

  final DocumentItem item;
  final bool busy;
  final VoidCallback onDelete;
  final VoidCallback onDisable;
  final VoidCallback onReindex;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            _FileIcon(type: item.fileType),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.filename,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '${item.chunkCount} 段 · ${_statusText(item.statusEnum)}${_dateSuffix(item.createdAt)}',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: const Color(0xff64748b),
                    ),
                  ),
                  if (item.statusEnum == DocumentStatus.failed &&
                      item.errorMsg != null) ...[
                    const SizedBox(height: 6),
                    Text(
                      item.errorMsg!,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: Color(0xffdc2626)),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: 10),
            _StatusPill(status: item.statusEnum),
            if (busy)
              const Padding(
                padding: EdgeInsets.all(12),
                child: SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              )
            else
              PopupMenuButton<_DocumentAction>(
                onSelected: (action) {
                  switch (action) {
                    case _DocumentAction.disable:
                      onDisable();
                    case _DocumentAction.reindex:
                      onReindex();
                    case _DocumentAction.delete:
                      onDelete();
                  }
                },
                itemBuilder: (context) => [
                  if (item.statusEnum == DocumentStatus.ready)
                    const PopupMenuItem(
                      value: _DocumentAction.disable,
                      child: Text('停用文档'),
                    ),
                  if (item.statusEnum == DocumentStatus.ready ||
                      item.statusEnum == DocumentStatus.failed)
                    const PopupMenuItem(
                      value: _DocumentAction.reindex,
                      child: Text('重建索引'),
                    ),
                  const PopupMenuItem(
                    value: _DocumentAction.delete,
                    child: Text('删除文档'),
                  ),
                ],
              ),
          ],
        ),
      ),
    );
  }

  String _statusText(DocumentStatus status) => switch (status) {
    DocumentStatus.ready => '就绪',
    DocumentStatus.failed => '失败',
    DocumentStatus.processing => '入库中',
    DocumentStatus.disabled => '已停用',
  };

  String _dateSuffix(DateTime? date) {
    if (date == null) {
      return '';
    }
    return ' · ${DateFormat('MM-dd HH:mm').format(date.toLocal())}';
  }
}

class _FileIcon extends StatelessWidget {
  const _FileIcon({required this.type});

  final String? type;

  @override
  Widget build(BuildContext context) {
    final icon = switch (type) {
      'pdf' => Icons.picture_as_pdf_outlined,
      'docx' => Icons.description_outlined,
      'md' => Icons.notes_outlined,
      _ => Icons.article_outlined,
    };
    return Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        color: const Color(0xffeff6ff),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Icon(icon, color: const Color(0xff2563eb)),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.status});

  final DocumentStatus status;

  @override
  Widget build(BuildContext context) {
    final (label, color) = switch (status) {
      DocumentStatus.ready => ('就绪', const Color(0xff16a34a)),
      DocumentStatus.failed => ('失败', const Color(0xffdc2626)),
      DocumentStatus.processing => ('入库中', const Color(0xfff59e0b)),
      DocumentStatus.disabled => ('已停用', const Color(0xff64748b)),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontWeight: FontWeight.w700,
          fontSize: 12,
        ),
      ),
    );
  }
}

enum _DocumentAction { disable, reindex, delete }
