package com.ai.mall.agent.customer.service.evidence;

import com.ai.mall.agent.customer.model.Document;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Complete-sentence quotation checks; equal numbers alone never establish entailment. */
@Component
public class EvidenceVerifier {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?(?:个工作日|工作日|天|小时|元|%|％)");
    private static final Pattern POINTS_LIMIT = Pattern.compile("最高抵扣[^。\\n]{0,18}?(百分之[零一二三四五六七八九十百]+|\\d+(?:\\.\\d+)?[%％])");
    public record Claim(String text, List<String> evidenceIds, String support) {}
    public record Report(String method, int checkedClaims, int unsupportedNumericClaims, List<Claim> claims, List<String> conflicts) {}

    public Report verify(String answer, List<Document> evidence) {
        if (answer == null || evidence == null || evidence.isEmpty()) return new Report("lexical-quotation-and-numeric", 0, 0, List.of(),List.of());
        List<Claim> claims = new ArrayList<>();
        int unsupported = 0;
        for (String sentence : answer.split("(?<=[。！？!?；;])|\\n")) {
            String text = sentence.trim();
            if (text.length() < 4) continue;
            List<String> numericFacts = NUMBER.matcher(text).results().map(m -> m.group()).toList();
            List<String> ids = evidence.stream().filter(d -> d.getContent() != null)
                    .filter(d -> Arrays.stream(d.getContent().split("(?<=[。！？!?；;])|\\n"))
                            .anyMatch(s -> normalize(s.trim()).equals(normalize(text))))
                    .map(Document::getId).filter(Objects::nonNull).distinct().toList();
            if (!numericFacts.isEmpty() && ids.isEmpty()) unsupported++;
            boolean overlap = !numericFacts.isEmpty() && evidence.stream().anyMatch(d -> d.getContent()!=null
                    && numericFacts.stream().allMatch(n -> normalize(d.getContent()).contains(normalize(n))));
            if (claims.size() < 30) claims.add(new Claim(text, ids, ids.isEmpty()
                    ? (overlap ? "numeric-overlap-unverified" : "unverified") : "lexical-match"));
        }
        Set<String> limits=new HashSet<>();
        if(answer.contains("积分") || answer.contains("抵扣")) {
            for(Document document:evidence) {
                if(document.getContent()==null) continue;
                POINTS_LIMIT.matcher(document.getContent()).results().forEach(match -> limits.add(normalizePercent(match.group(1))));
            }
        }
        List<String> conflicts=limits.size()>1?List.of("积分抵扣上限存在不一致："+String.join(" / ",new TreeSet<>(limits))):List.of();
        return new Report("lexical-quotation-and-numeric", claims.size(), unsupported, List.copyOf(claims),conflicts);
    }

    private String normalizePercent(String value) {
        if(!value.startsWith("百分之")) return value.replace('％','%');
        String number=value.substring(3);
        String digits="零一二三四五六七八九";
        if(number.contains("十")) {
            String[] parts=number.split("十",-1);
            int tens=parts[0].isEmpty()?1:digits.indexOf(parts[0]);
            int units=parts.length<2||parts[1].isEmpty()?0:digits.indexOf(parts[1]);
            if(tens>=0 && units>=0) return (tens*10+units)+"%";
        }
        return value;
    }

    private String normalize(String text) { return text.replaceAll("[\\s，,。；;：:]", ""); }
}
