import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../core/widgets/state_views.dart';
import '../../data/models/document_models.dart';
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
    Future.microtask(() => ref.read(documentsViewModelProvider.notifier).load());
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
              onAction: () => ref
                  .read(documentsViewModelProvider.notifier)
                  .pickAndUpload(),
            );
          }
          return RefreshIndicator(
            onRefresh: () =>
                ref.read(documentsViewModelProvider.notifier).load(),
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemBuilder: (context, index) => _DocumentCard(
                item: state.items[index],
                onDelete: () => ref
                    .read(documentsViewModelProvider.notifier)
                    .delete(state.items[index].documentId),
              ),
              separatorBuilder: (context, index) => const SizedBox(height: 12),
              itemCount: state.items.length,
            ),
          );
        },
      ),
    );
  }
}

class _DocumentCard extends StatelessWidget {
  const _DocumentCard({required this.item, required this.onDelete});

  final DocumentItem item;
  final VoidCallback onDelete;

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
            IconButton(
              onPressed: onDelete,
              icon: const Icon(Icons.delete_outline),
              tooltip: '删除',
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
