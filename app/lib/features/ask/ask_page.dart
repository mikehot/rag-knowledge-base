import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/models/ask_models.dart';
import '../auth/auth_view_model.dart';
import 'ask_view_model.dart';

class AskPage extends ConsumerStatefulWidget {
  const AskPage({super.key});

  @override
  ConsumerState<AskPage> createState() => _AskPageState();
}

class _AskPageState extends ConsumerState<AskPage> {
  final _controller = TextEditingController();
  final _scrollController = ScrollController();

  @override
  void dispose() {
    _controller.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(askViewModelProvider);
    ref.listen(askViewModelProvider, (previous, next) => _scrollToBottom());
    return Scaffold(
      appBar: AppBar(
        title: const Text('智能问答'),
        actions: [
          IconButton(
            onPressed: () => ref.read(authViewModelProvider.notifier).logout(),
            icon: const Icon(Icons.logout),
            tooltip: '退出登录',
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: state.messages.isEmpty
                ? const _AskEmptyView()
                : ListView.separated(
                    controller: _scrollController,
                    padding: const EdgeInsets.all(16),
                    itemBuilder: (context, index) {
                      final message = state.messages[index];
                      return _ChatBubble(
                        message: message,
                        onRate: (rating) => ref
                            .read(askViewModelProvider.notifier)
                            .rate(message.id, rating),
                      );
                    },
                    separatorBuilder: (context, index) =>
                        const SizedBox(height: 12),
                    itemCount: state.messages.length,
                  ),
          ),
          if (state.error != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      state.error!,
                      style: const TextStyle(color: Color(0xffdc2626)),
                    ),
                  ),
                  TextButton(
                    onPressed: state.sending
                        ? null
                        : () => ref
                              .read(askViewModelProvider.notifier)
                              .retryLast(),
                    child: const Text('重试'),
                  ),
                ],
              ),
            ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _controller,
                      minLines: 1,
                      maxLines: 4,
                      textInputAction: TextInputAction.send,
                      onSubmitted: (_) => _send(),
                      decoration: const InputDecoration(
                        hintText: '输入你的问题',
                        isDense: true,
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  IconButton.filled(
                    onPressed: state.sending ? null : _send,
                    icon: const Icon(Icons.send_rounded),
                    tooltip: '发送',
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  void _send() {
    final text = _controller.text;
    _controller.clear();
    ref.read(askViewModelProvider.notifier).send(text);
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) {
        return;
      }
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent,
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOut,
      );
    });
  }
}

class _AskEmptyView extends StatelessWidget {
  const _AskEmptyView();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Text(
          '上传文档后即可提问',
          style: Theme.of(context).textTheme.titleMedium?.copyWith(
            color: const Color(0xff64748b),
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }
}

class _ChatBubble extends StatelessWidget {
  const _ChatBubble({required this.message, required this.onRate});

  final ChatMessage message;
  final ValueChanged<String> onRate;

  @override
  Widget build(BuildContext context) {
    final align = message.fromUser
        ? Alignment.centerRight
        : Alignment.centerLeft;
    final maxWidth = MediaQuery.sizeOf(context).width * 0.78;
    return Align(
      alignment: align,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: maxWidth),
        child: message.fromUser
            ? _UserBubble(text: message.text)
            : _AnswerCard(message: message, onRate: onRate),
      ),
    );
  }
}

class _UserBubble extends StatelessWidget {
  const _UserBubble({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xff2563eb),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        child: Text(text, style: const TextStyle(color: Colors.white)),
      ),
    );
  }
}

class _AnswerCard extends StatelessWidget {
  const _AnswerCard({required this.message, required this.onRate});

  final ChatMessage message;
  final ValueChanged<String> onRate;

  @override
  Widget build(BuildContext context) {
    return Card(
      color: const Color(0xfff1f5f9),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (message.loading)
              const Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                  SizedBox(width: 10),
                  Text('思考中...'),
                ],
              )
            else
              Text(message.text),
            if (!message.loading) ...[
              const SizedBox(height: 10),
              _AnswerMeta(message: message),
              if (message.requestId != null) ...[
                const SizedBox(height: 8),
                _FeedbackBar(message: message, onRate: onRate),
              ],
            ],
            if (!message.loading && message.sources.isNotEmpty) ...[
              const Divider(height: 22),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: message.sources
                    .map((source) => _SourceChip(source: source))
                    .toList(),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _AnswerMeta extends StatelessWidget {
  const _AnswerMeta({required this.message});

  final ChatMessage message;

  @override
  Widget build(BuildContext context) {
    final status = message.found
        ? (message.grounded ? '已引用资料回答' : '已找到资料')
        : '未找到足够资料';
    final details = <String>[status];
    if (message.latencyMs != null && message.latencyMs! > 0) {
      details.add('${message.latencyMs} ms');
    }
    if (message.tokenUsage != null && message.tokenUsage! > 0) {
      details.add('${message.tokenUsage} tokens');
    }
    if (message.failureReason != null) {
      details.add(_failureText(message.failureReason!));
    }
    final timings = message.timings;
    if (timings != null) {
      details.add(
        '检索 ${timings.retrievalMs} ms · 生成 ${timings.generationMs} ms',
      );
    }
    return Text(
      details.join(' · '),
      style: Theme.of(context).textTheme.bodySmall?.copyWith(
        color: message.found
            ? const Color(0xff166534)
            : const Color(0xffb45309),
      ),
    );
  }

  String _failureText(String reason) => switch (reason) {
    'RETRIEVAL_MISS' => '未命中资料',
    'INSUFFICIENT_CONTEXT' => '资料不足',
    'GENERATION_TIMEOUT' => '模型超时',
    'GENERATION_ERROR' => '模型失败',
    'STRUCTURED_OUTPUT_INVALID' => '回答格式异常',
    'CITATION_MISSING' => '引用缺失',
    _ => '处理失败',
  };
}

class _FeedbackBar extends StatelessWidget {
  const _FeedbackBar({required this.message, required this.onRate});

  final ChatMessage message;
  final ValueChanged<String> onRate;

  @override
  Widget build(BuildContext context) {
    final disabled = message.feedbackSending || message.feedbackRating != null;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          message.feedbackRating == null ? '回答有帮助吗？' : '感谢反馈',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        IconButton(
          visualDensity: VisualDensity.compact,
          onPressed: disabled ? null : () => onRate('HELPFUL'),
          icon: Icon(
            message.feedbackRating == 'HELPFUL'
                ? Icons.thumb_up
                : Icons.thumb_up_outlined,
          ),
          tooltip: '有帮助',
        ),
        IconButton(
          visualDensity: VisualDensity.compact,
          onPressed: disabled ? null : () => onRate('NOT_HELPFUL'),
          icon: Icon(
            message.feedbackRating == 'NOT_HELPFUL'
                ? Icons.thumb_down
                : Icons.thumb_down_outlined,
          ),
          tooltip: '没帮助',
        ),
      ],
    );
  }
}

class _SourceChip extends StatelessWidget {
  const _SourceChip({required this.source});

  final ChunkSource source;

  @override
  Widget build(BuildContext context) {
    return ActionChip(
      avatar: const Icon(Icons.article_outlined, size: 16),
      label: Text('${source.filename} · ${source.locator}'),
      onPressed: () => showModalBottomSheet<void>(
        context: context,
        showDragHandle: true,
        builder: (context) => Padding(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 28),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${source.filename} · ${source.locator}',
                style: Theme.of(
                  context,
                ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 12),
              Text(source.snippet),
            ],
          ),
        ),
      ),
    );
  }
}
