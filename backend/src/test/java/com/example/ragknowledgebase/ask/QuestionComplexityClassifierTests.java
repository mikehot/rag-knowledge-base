package com.example.ragknowledgebase.ask;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuestionComplexityClassifierTests {
    private final QuestionComplexityClassifier classifier = new QuestionComplexityClassifier();

    @Test
    void recognizesExplicitMultiPartLanguage() {
        assertThat(classifier.isComplex("请同时说明配网步骤以及远程开门条件。"))
            .isTrue();
        assertThat(classifier.isComplex("请综合说明安装验收指标，以及失败后的检查项。"))
            .isTrue();
    }

    @Test
    void recognizesMultipleInterrogatives() {
        assertThat(classifier.isComplex("多久可以换货，运费由谁承担？"))
            .isTrue();
        assertThat(classifier.isComplex("哪个型号支持远程开门，有什么安全条件？"))
            .isTrue();
    }

    @Test
    void keepsSimpleQuestionOnDefaultBudget() {
        assertThat(classifier.isComplex("安装门需要适配多厚的门？"))
            .isFalse();
        assertThat(classifier.isComplex(""))
            .isFalse();
    }
}
