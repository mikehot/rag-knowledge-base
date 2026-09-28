package com.example.ragknowledgebase.document;

/**
 * Raised when an index task targets an older content version than the document now has.
 * The newer version has its own task, so this one is dropped rather than treated as a failure.
 */
public class SupersededIndexTaskException extends RuntimeException {
    public SupersededIndexTaskException() {
        super("文档内容已更新，放弃过期的索引结果");
    }
}
