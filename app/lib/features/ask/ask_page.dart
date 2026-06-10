import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/models/ask_models.dart';
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
      appBar: AppBar(title: const Text('智能问答')),
      body: Column(
        children: [
          Expanded(
            child: state.messages.isEmpty
                ? const _AskEmptyView()
                : ListView.separated(
                    controller: _scrollController,
                    padding: const EdgeInsets.all(16),
                    itemBuilder: (context, index) =>
                        _ChatBubble(message: state.messages[index]),
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
  const _ChatBubble({required this.message});

  final ChatMessage message;

  @override
  Widget build(BuildContext context) {
    final align = message.fromUser ? Alignment.centerRight : Alignment.centerLeft;
    final maxWidth = MediaQuery.sizeOf(context).width * 0.78;
    return Align(
      alignment: align,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: maxWidth),
        child: message.fromUser
            ? _UserBubble(text: message.text)
            : _AnswerCard(message: message),
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
  const _AnswerCard({required this.message});

  final ChatMessage message;

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
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
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
