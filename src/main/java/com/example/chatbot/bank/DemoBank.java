package com.example.chatbot.bank;

import java.math.BigDecimal;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * In-memory stand-in for core banking. All customers, accounts and cards are fictional. In a real
 * system each method would be a call to a system of record (REST, MQ, mainframe adapter).
 */
@Component
public class DemoBank {

  public record Customer(String id, String name, String language) {}

  public record Account(String type, String last4, BigDecimal balance, String currency) {}

  public record Card(String network, String last4, String status) {}

  public record ServiceRequest(
      String reference, String customerId, String category, String details, String status) {}

  /** Thrown when the (simulated) core banking system does not answer. Workers retry on it. */
  public static class CoreBankingUnavailableException extends RuntimeException {
    public CoreBankingUnavailableException(String message) {
      super(message);
    }
  }

  private final Map<String, Customer> customers = new LinkedHashMap<>();
  private final Map<String, List<Account>> accounts = new ConcurrentHashMap<>();
  private final Map<String, List<Card>> cards = new ConcurrentHashMap<>();
  private final Map<String, ServiceRequest> serviceRequests = new ConcurrentHashMap<>();
  private final AtomicInteger nextRequest = new AtomicInteger(10001);
  private volatile boolean coreBankingAvailable = true;

  public DemoBank() {
    reset();
  }

  /** Restores the starting data: all cards active, no service requests. */
  public synchronized void reset() {
    customers.clear();
    customers.put("C-1001", new Customer("C-1001", "Amélie Tremblay", "fr"));
    customers.put("C-1002", new Customer("C-1002", "Marc Gagnon", "en"));
    customers.put("C-1003", new Customer("C-1003", "Priya Natarajan", "en"));
    customers.put("C-1004", new Customer("C-1004", "Sofia Lindqvist", "en"));

    accounts.put(
        "C-1001",
        List.of(
            new Account("Everyday chequing", "2231", new BigDecimal("2418.55"), "CAD"),
            new Account("High-interest savings", "7740", new BigDecimal("12950.00"), "CAD")));
    accounts.put(
        "C-1002",
        List.of(
            new Account("Unlimited chequing", "5521", new BigDecimal("834.12"), "CAD"),
            new Account("Tax-free savings account", "3307", new BigDecimal("6200.00"), "CAD")));
    accounts.put(
        "C-1003", List.of(new Account("Student chequing", "6610", new BigDecimal("312.40"), "CAD")));
    accounts.put(
        "C-1004",
        List.of(
            new Account("Everyday chequing", "4083", new BigDecimal("5120.75"), "CAD"),
            new Account("US dollar savings", "9921", new BigDecimal("1800.00"), "USD")));

    cards.put(
        "C-1001",
        new ArrayList<>(
            List.of(new Card("Visa credit", "4417", "ACTIVE"), new Card("Debit", "9012", "ACTIVE"))));
    cards.put("C-1002", new ArrayList<>(List.of(new Card("Mastercard credit", "8820", "ACTIVE"))));
    cards.put("C-1003", new ArrayList<>(List.of(new Card("Visa credit", "1175", "ACTIVE"))));
    cards.put(
        "C-1004",
        new ArrayList<>(
            List.of(new Card("Mastercard credit", "3390", "LOCKED"), new Card("Debit", "7206", "ACTIVE"))));

    serviceRequests.clear();
    coreBankingAvailable = true;
  }

  public List<Customer> customers() {
    return List.copyOf(customers.values());
  }

  public Optional<Customer> customer(String customerId) {
    return Optional.ofNullable(customers.get(customerId));
  }

  /** Accounts and cards of one customer, shaped for the agent. */
  public Map<String, Object> summary(String customerId) {
    checkAvailable();
    Customer customer =
        customer(customerId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown customer " + customerId));
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("customerName", customer.name());
    summary.put("preferredLanguage", customer.language());
    summary.put("accounts", accounts.getOrDefault(customerId, List.of()));
    summary.put("cards", List.copyOf(cards.getOrDefault(customerId, List.of())));
    return summary;
  }

  /**
   * Locks a card.
   *
   * @return the card after the change, or empty if the customer has no card with these digits
   */
  public synchronized Optional<Card> lockCard(String customerId, String last4) {
    checkAvailable();
    List<Card> list = cards.getOrDefault(customerId, List.of());
    for (int i = 0; i < list.size(); i++) {
      Card card = list.get(i);
      if (card.last4().equals(last4)) {
        Card locked = new Card(card.network(), card.last4(), "LOCKED");
        list.set(i, locked);
        return Optional.of(locked);
      }
    }
    return Optional.empty();
  }

  public Optional<Card> card(String customerId, String last4) {
    return cards.getOrDefault(customerId, List.of()).stream()
        .filter(c -> c.last4().equals(last4))
        .findFirst();
  }

  public ServiceRequest openServiceRequest(String customerId, String category, String details) {
    checkAvailable();
    String reference = "SR-" + Year.now() + "-" + nextRequest.getAndIncrement();
    ServiceRequest request = new ServiceRequest(reference, customerId, category, details, "OPEN");
    serviceRequests.put(reference, request);
    return request;
  }

  public List<ServiceRequest> serviceRequests() {
    return List.copyOf(serviceRequests.values());
  }

  /** Lets tests simulate an outage of the core banking system. */
  public void setCoreBankingAvailable(boolean available) {
    this.coreBankingAvailable = available;
  }

  private void checkAvailable() {
    if (!coreBankingAvailable) {
      throw new CoreBankingUnavailableException("Core banking did not answer");
    }
  }
}
