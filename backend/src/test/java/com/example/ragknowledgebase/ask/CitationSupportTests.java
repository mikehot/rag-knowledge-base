package com.example.ragknowledgebase.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CitationSupportTests {
    private static final String REST_AND_HOLIDAYS =
        "第三十八条　用人单位应当保证劳动者每周至少休息一日。第三十九条　企业因生产特点不能实行本法第三十六条、"
            + "第三十八条规定的，经劳动行政部门批准，可以实行其他工作和休息办法。第四十条　用人单位在下列节日期间应当依法安排劳动者休假：元旦；春节。";
    private static final String MATERNITY =
        "第六十一条　不得安排女职工在怀孕期间从事国家规定的第三级体力劳动强度的劳动。第六十二条　女职工生育享受不少于九十天的产假。"
            + "第六十三条　不得安排女职工在哺乳未满一周岁的婴儿期间从事国家规定的第三级体力劳动强度的劳动。";
    private static final String UNRELATED = "第五十条　工资应当以货币形式按月支付给劳动者本人。不得克扣或者无故拖欠劳动者的工资。";

    private final CitationSupport support = new CitationSupport();

    @Test
    void keepsCitationsThatContainTheAnswer() {
        var result = support.evaluate(
            "女职工生育享受不少于九十天的产假。",
            List.of(MATERNITY, REST_AND_HOLIDAYS),
            List.of(1)
        );

        assertThat(result.outcome()).isEqualTo(CitationSupport.Outcome.SUPPORTED);
        assertThat(result.indexes()).containsExactly(1);
        assertThat(result.citedSupport()).isGreaterThanOrEqualTo(CitationSupport.KEEP_AT_LEAST);
    }

    @Test
    void repairsACitationThatPointsAtTheWrongRetrievedChunk() {
        // Seen in a live run: the answer was right, but the model cited the rest-and-holiday chunk.
        var result = support.evaluate(
            "女职工生育享受不少于九十天的产假。",
            List.of(REST_AND_HOLIDAYS, MATERNITY, UNRELATED),
            List.of(1)
        );

        assertThat(result.outcome()).isEqualTo(CitationSupport.Outcome.REPAIRED);
        assertThat(result.indexes()).containsExactly(2);
        assertThat(result.bestSupport()).isGreaterThan(result.citedSupport());
    }

    @Test
    void treatsChineseAndArabicNumeralsAsTheSameQuantity() {
        var result = support.evaluate(
            "试用期最长不得超过 2 个月。",
            List.of("劳动合同期限一年以上不满三年的，试用期不得超过二个月。"),
            List.of(1)
        );

        assertThat(result.outcome()).isEqualTo(CitationSupport.Outcome.SUPPORTED);
    }

    @Test
    void ignoresListNumbering() {
        var result = support.evaluate(
            "1. 女职工生育享受不少于九十天的产假。\n2. 不得安排哺乳期女职工延长工作时间。",
            List.of(MATERNITY),
            List.of(1)
        );

        assertThat(CitationSupport.units("1. 产假\n2. 哺乳")).doesNotContain("1", "2");
        assertThat(result.outcome()).isEqualTo(CitationSupport.Outcome.SUPPORTED);
    }

    @Test
    void flagsAnAnswerThatNoRetrievedChunkSupports() {
        var result = support.evaluate(
            "公司食堂每周三提供免费水果拼盘，请前往三楼领取。",
            List.of(REST_AND_HOLIDAYS, MATERNITY, UNRELATED),
            List.of(2)
        );

        assertThat(result.outcome()).isEqualTo(CitationSupport.Outcome.UNSUPPORTED);
        assertThat(result.indexes()).containsExactly(2);
    }

    @Test
    void keepsTheModelsCitationWhenNothingBetterWasRetrieved() {
        // Partly supported by the cited chunk and by no other: weak, but there is nothing to repair with.
        var result = support.evaluate(
            "女职工生育享受不少于九十天的产假，并且公司额外提供三个月带薪陪护。",
            List.of(MATERNITY, UNRELATED),
            List.of(1)
        );

        assertThat(result.outcome()).isIn(CitationSupport.Outcome.UNCHANGED, CitationSupport.Outcome.SUPPORTED);
        assertThat(result.indexes()).containsExactly(1);
    }

    @Test
    void repairsWithAtMostThreeChunksInRankOrder() {
        String a = "苹果的价格是十元，橙子的价格是八元。";
        String b = "香蕉的价格是六元，葡萄的价格是十二元。";
        String c = "西瓜的价格是二十元，梨的价格是五元。";
        String d = "桃子的价格是九元，李子的价格是七元。";

        var result = support.evaluate(
            "苹果十元，橙子八元，香蕉六元，葡萄十二元，西瓜二十元，梨五元，桃子九元，李子七元。",
            List.of(UNRELATED, d, c, b, a),
            List.of(1)
        );

        assertThat(result.outcome()).isEqualTo(CitationSupport.Outcome.REPAIRED);
        assertThat(result.indexes()).hasSizeLessThanOrEqualTo(CitationSupport.MAX_REPAIRED_SOURCES);
        assertThat(result.indexes()).isSorted();
        assertThat(result.indexes()).doesNotContain(1);
    }

    @Test
    void anAnswerWithNoContentUnitsIsLeftAlone() {
        var result = support.evaluate("是", List.of(UNRELATED), List.of(1));

        assertThat(result.outcome()).isEqualTo(CitationSupport.Outcome.SUPPORTED);
        assertThat(result.indexes()).containsExactly(1);
    }

    @Test
    void modeParsingFallsBackToRepair() {
        assertThat(CitationSupportMode.from(null)).isEqualTo(CitationSupportMode.REPAIR);
        assertThat(CitationSupportMode.from("  ")).isEqualTo(CitationSupportMode.REPAIR);
        assertThat(CitationSupportMode.from("enforce")).isEqualTo(CitationSupportMode.ENFORCE);
        assertThat(CitationSupportMode.from("off")).isEqualTo(CitationSupportMode.OFF);
        assertThat(CitationSupportMode.from("strict")).isEqualTo(CitationSupportMode.REPAIR);
    }
}
