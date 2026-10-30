package com.example.chatbot.simulate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scripted stand-in for the language model, used only in the {@code simulate} profile. It reads the
 * customer's message, picks tools the way a model would, and writes replies from the tool results. It
 * never invents data: every balance, fee, card and reference in a reply comes from a tool result.
 *
 * <p>Each call to {@link #next} is one "model call": given the user prompt and the results of the tools
 * it asked for so far in this turn, it returns the next step.
 */
public class SimulatedAgent {

  /** What the agent does next. */
  public sealed interface Step permits CallTool, Reply, OffRail {}

  public record CallTool(String elementId, Map<String, Object> arguments) implements Step {}

  public record Reply(String text) implements Step {}

  public record OffRail(String errorCode) implements Step {}

  /** A tool the agent called earlier in this turn, with its result (null until it is back). */
  public record ToolResult(String name, Object content) {}

  enum Intent { GUARDRAIL, LOST_CARD, ADVISOR, FEES, SERVICE_REQUEST, BALANCE, CLOSE, HELP }

  private static final Pattern FOUR_DIGITS = Pattern.compile("\\b(\\d{4})\\b");

  public Step next(String userPrompt, List<ToolResult> doneThisTurn) {
    String text = userPrompt == null ? "" : userPrompt.toLowerCase(Locale.ROOT);
    return switch (intent(text)) {
      case GUARDRAIL -> new OffRail("MAXIMUM_NUMBER_OF_MODEL_CALLS_REACHED");
      case LOST_CARD -> lostCard(text, doneThisTurn);
      case ADVISOR -> advisor(userPrompt, doneThisTurn);
      case FEES -> fees(text, doneThisTurn);
      case SERVICE_REQUEST -> serviceRequest(userPrompt, text, doneThisTurn);
      case BALANCE -> balance(doneThisTurn);
      case CLOSE -> doneThisTurn.isEmpty()
          ? new CallTool("CloseConversation", Map.of())
          : new Reply("Thank you for chatting with us. Goodbye.");
      case HELP -> new Reply(
          "I can show your accounts and balances, lock a lost card, look up branch hours and fees, "
              + "open a service request, or connect you with an advisor. What do you need?");
    };
  }

  static Intent intent(String t) {
    if (has(t, "test the guardrail", "off-rail", "off rail")) return Intent.GUARDRAIL;
    if (has(t, "lost", "stolen", "perdu", "volé", "vole ", "lock my card", "lock my")) return Intent.LOST_CARD;
    if (has(t, "person", "human", "advisor", "adviser", "mortgage", "loan", "invest", "fraud", "conseiller", "someone"))
      return Intent.ADVISOR;
    if (has(t, "fee", "cost", "price", "charge", "frais", "hours", "open on", "opening", "branch", "atm", "wire", "e-transfer"))
      return Intent.FEES;
    if (has(t, "moved", "address", "dispute", "cheque", "check book", "replace", "new card", "adresse"))
      return Intent.SERVICE_REQUEST;
    if (has(t, "balance", "solde", "account", "how much do i have", "compte")) return Intent.BALANCE;
    if (has(t, "bye", "that's all", "thats all", "nothing else", "merci", "thank", "au revoir", "no thanks"))
      return Intent.CLOSE;
    return Intent.HELP;
  }

  private Step balance(List<ToolResult> done) {
    if (done.isEmpty()) {
      return new CallTool("GetAccountSummary", Map.of());
    }
    Map<String, Object> summary = asMap(done.get(0).content());
    StringBuilder reply = new StringBuilder("Here are your accounts:");
    for (Map<String, Object> account : asList(summary.get("accounts"))) {
      reply.append("\n- ")
          .append(account.get("type"))
          .append(" ending ")
          .append(account.get("last4"))
          .append(": ")
          .append(money(account.get("balance")))
          .append(' ')
          .append(account.get("currency"));
    }
    return new Reply(reply.toString());
  }

  private Step lostCard(String text, List<ToolResult> done) {
    if (done.isEmpty()) {
      return new CallTool("GetAccountSummary", Map.of());
    }
    Map<String, Object> card = pickCard(text, asMap(done.get(0).content()));
    if (card == null) {
      return new Reply("I don't see a card on your profile. I can connect you with an advisor if you like.");
    }
    String label = card.get("network") + " card ending " + card.get("last4");
    if (done.size() == 1) {
      if ("LOCKED".equals(card.get("status"))) {
        return new Reply("Your " + label + " is already locked. Nothing more is needed to block it.");
      }
      return new CallTool(
          "AskCustomer",
          Map.of("question", "I can lock your " + label + " now. New purchases and withdrawals will be refused. Shall I lock it?"));
    }
    if (done.size() == 2) {
      if (!isYes(String.valueOf(asMap(done.get(1).content()).get("answer")))) {
        return new Reply("All right, I left your " + label + " active. Tell me if you change your mind.");
      }
      return new CallTool("LockCard", Map.of("cardLast4", card.get("last4"), "customerConfirmed", true));
    }
    Map<String, Object> lock = asMap(done.get(2).content());
    if (Boolean.TRUE.equals(lock.get("locked"))) {
      return new Reply("Done. Your " + label + " is locked. If you want a replacement card, I can open a request for it.");
    }
    return new Reply("I could not lock the card: " + lock.get("reason"));
  }

  private Step fees(String text, List<ToolResult> done) {
    if (done.isEmpty()) {
      return new CallTool("LookUpBranchHoursAndFees", Map.of("topic", topic(text)));
    }
    Map<String, Object> faq = asMap(done.get(0).content());
    return new Reply("From our published hours and fees: " + faq.get("answer"));
  }

  private Step serviceRequest(String original, String text, List<ToolResult> done) {
    String category =
        has(text, "moved", "address", "adresse") ? "address-change"
            : has(text, "dispute") ? "transaction-dispute"
            : has(text, "cheque", "check book") ? "cheque-order"
            : has(text, "replace", "new card") ? "card-replacement"
            : "other";
    if (done.isEmpty()) {
      return new CallTool(
          "AskCustomer",
          Map.of("question", "I will open " + (category.startsWith("a") || category.startsWith("o") ? "an " : "a ")
              + category.replace('-', ' ') + " request with this note: \""
              + original.trim() + "\". Shall I go ahead?"));
    }
    if (done.size() == 1) {
      if (!isYes(String.valueOf(asMap(done.get(0).content()).get("answer")))) {
        return new Reply("OK, I did not open a request. Anything else?");
      }
      return new CallTool("CreateServiceRequest", Map.of("category", category, "details", original.trim()));
    }
    Map<String, Object> request = asMap(done.get(1).content());
    if (!Boolean.TRUE.equals(request.get("created"))) {
      return new Reply("The request was not created: " + request.get("reason"));
    }
    return new Reply("Your request is open. Reference " + request.get("reference")
        + ". Expect a first response " + request.get("expectedFirstResponse") + ".");
  }

  private Step advisor(String original, List<ToolResult> done) {
    if (done.isEmpty()) {
      return new CallTool(
          "HandOffToAdvisor",
          Map.of("summary", "The customer asked for a person in the chat assistant.", "question", original.trim()));
    }
    Map<String, Object> answer = asMap(done.get(0).content());
    return new Reply("An advisor answered: " + answer.get("advisorAnswer"));
  }

  // ---------------------------------------------------------------- helpers

  static String topic(String t) {
    if (has(t, "atm")) return "atm-fees";
    if (has(t, "e-transfer", "interac", "transfer")) return "interac-e-transfer";
    if (has(t, "wire")) return "international-wire";
    if (has(t, "replace", "replacement")) return "card-replacement";
    if (has(t, "hours", "open", "close", "branch", "heures")) return "branch-hours";
    if (has(t, "monthly", "account fee", "plan", "fee")) return "monthly-account-fees";
    return "other";
  }

  private static Map<String, Object> pickCard(String text, Map<String, Object> summary) {
    List<Map<String, Object>> cards = asList(summary.get("cards"));
    Matcher m = FOUR_DIGITS.matcher(text);
    if (m.find()) {
      for (Map<String, Object> c : cards) {
        if (m.group(1).equals(c.get("last4"))) return c;
      }
    }
    boolean debit = text.contains("debit");
    for (Map<String, Object> c : cards) {
      boolean isDebit = String.valueOf(c.get("network")).toLowerCase(Locale.ROOT).contains("debit");
      if (isDebit == debit) return c;
    }
    return cards.isEmpty() ? null : cards.get(0);
  }

  static boolean isYes(String answer) {
    String a = answer.toLowerCase(Locale.ROOT).trim();
    if (a.startsWith("no") || a.startsWith("non") || a.contains("don't") || a.contains("do not")) {
      return false;
    }
    return a.startsWith("y") || a.startsWith("oui") || has(a, "go ahead", "please", "sure", "ok", "do it", "confirm");
  }

  private static boolean has(String text, String... words) {
    for (String w : words) {
      if (text.contains(w)) return true;
    }
    return false;
  }

  private static String money(Object value) {
    try {
      return String.format(Locale.CANADA, "%,.2f", new BigDecimal(String.valueOf(value)));
    } catch (NumberFormatException e) {
      return String.valueOf(value);
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> asMap(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> asList(Object o) {
    List<Map<String, Object>> list = new ArrayList<>();
    if (o instanceof List<?> l) {
      l.forEach(e -> list.add(asMap(e)));
    }
    return list;
  }
}
