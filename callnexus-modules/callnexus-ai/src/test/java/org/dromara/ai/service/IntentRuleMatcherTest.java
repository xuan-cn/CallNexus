package org.dromara.ai.service;

import org.dromara.ai.domain.AiIntentMatchRule;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@Tag("dev")
class IntentRuleMatcherTest {
    private AiIntentMatchRule rule(String mode, List<List<String>> groups) {
        var rule = new AiIntentMatchRule();
        rule.setPolarity("POSITIVE"); rule.setMode(mode); rule.setGroups(groups);
        IntentRuleMatcher.validate(List.of(rule)); return rule;
    }
    @Test void anyMatchesOneOfManyWords() {
        assertThat(IntentRuleMatcher.matchedWords(rule("ANY", List.of(List.of("爸爸", "母亲", "爱人"))), "我是他爸爸")).containsExactly("爸爸");
    }
    @Test void allRequiresEveryGroupButOnlyOneWordPerGroup() {
        var r = rule("ALL", List.of(List.of("不会", "不懂"), List.of("上传", "提交材料")));
        assertThat(IntentRuleMatcher.matchedWords(r, "我不懂怎么上传")).containsExactly("不懂", "上传");
        assertThat(IntentRuleMatcher.matchedWords(r, "上传好了")).isEmpty();
    }
    @Test void exactIgnoresPunctuationAndSpacesByDefault() {
        var r = rule("EXACT", List.of(List.of("是的")));
        assertThat(IntentRuleMatcher.matchedWords(r, " 是，的！　")).containsExactly("是的");
        assertThat(IntentRuleMatcher.matchedWords(r, "不是的")).isEmpty();
    }
    @Test void punctuationCanRemainSignificant() {
        var r = rule("EXACT", List.of(List.of("是的"))); r.setIgnorePunctuation(false);
        assertThat(IntentRuleMatcher.matchedWords(r, "是的！")).isEmpty();
    }
    @Test void startsAndEndsAreAnchored() {
        assertThat(IntentRuleMatcher.matchedWords(rule("PREFIX", List.of(List.of("我是"))), "我是本人")).isNotEmpty();
        assertThat(IntentRuleMatcher.matchedWords(rule("PREFIX", List.of(List.of("我是"))), "请问我是吗")).isEmpty();
        assertThat(IntentRuleMatcher.matchedWords(rule("SUFFIX", List.of(List.of("可以吗"))), "明天可以吗？")).isNotEmpty();
    }
    @Test void regexSyntaxIsLiteralAndCannotExecute() {
        var r = rule("ANY", List.of(List.of("(a+)+$"))); r.setIgnorePunctuation(false);
        assertThat(IntentRuleMatcher.matchedWords(r, "aaaaaaaa")).isEmpty();
        assertThat(IntentRuleMatcher.matchedWords(r, "包含(a+)+$字符")).isNotEmpty();
    }
    @Test void invalidGroupsAndTypesAreRejected() {
        var r = new AiIntentMatchRule(); r.setPolarity("POSITIVE"); r.setMode("REGEX");
        assertThatThrownBy(() -> IntentRuleMatcher.validate(List.of(r))).isInstanceOf(ServiceException.class);
        r.setMode("ANY"); r.setGroups(List.of(List.of("!!!")));
        assertThatThrownBy(() -> IntentRuleMatcher.validate(List.of(r))).isInstanceOf(ServiceException.class);
        r.setGroups(List.of(List.of()));
        assertThatThrownBy(() -> IntentRuleMatcher.validate(List.of(r))).isInstanceOf(ServiceException.class);
    }
    @Test void oversizedRulesAreRejected() {
        var r = rule("ANY", List.of(List.of("a")));
        assertThatThrownBy(() -> IntentRuleMatcher.validate(java.util.Collections.nCopies(41, r))).isInstanceOf(ServiceException.class);
        r.setGroups(List.of(List.of("a".repeat(129))));
        assertThatThrownBy(() -> IntentRuleMatcher.validate(List.of(r))).isInstanceOf(ServiceException.class);
    }
}
