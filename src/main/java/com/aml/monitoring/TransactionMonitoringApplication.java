package com.aml.monitoring;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.PostMapping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.net.HttpURLConnection;
import java.net.URL;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootApplication(
exclude = {
R2dbcAutoConfiguration.class
}
)

@RestController
public class TransactionMonitoringApplication {

// -------------------------------------------------
// INJECTED CONFIG  (values come from application.properties)
// -------------------------------------------------

@Value("${spring.datasource.url}")
private String dbUrl;

@Value("${spring.datasource.username}")
private String dbUser;

@Value("${spring.datasource.password}")
private String dbPassword;

@Value("${screening.script.path}")
private String screeningScriptPath;

@PostMapping("/run-screening")
public ResponseEntity<String> runScreening() {

    try {

        ProcessBuilder pb =
            new ProcessBuilder(

                "cmd.exe",

                "/c",

                screeningScriptPath
            );

        pb.start();

        return ResponseEntity.ok(
            "Started"
        );

    }

    catch (Exception e) {

        return ResponseEntity
            .status(500)
            .body(e.getMessage());
    }
}

// -------------------------------------------------
// MAIN
// -------------------------------------------------

public static void main(String[] args) {

    SpringApplication.run(
            TransactionMonitoringApplication.class,
            args
    );
}

// -------------------------------------------------
// TRANSACTION MODEL
// -------------------------------------------------

static class Transaction {

    public String customerId;

    public String clientName;

    public double amount;

    public String transactionDate;

    public String segment;

    public String voucherType;

    public String remarks;

    public String instrumentType;

    public String transactionReference;

    public Transaction(
            String customerId,
            String clientName,
            double amount,
            String transactionDate,
            String segment,
            String voucherType,
            String remarks,
            String instrumentType,
            String transactionReference
    ) {

        this.customerId = customerId;

        this.clientName = clientName;

        this.amount = amount;

        this.transactionDate = transactionDate;

        this.segment = segment;

        this.voucherType = voucherType;

        this.remarks = remarks;

        this.instrumentType = instrumentType;

        this.transactionReference = transactionReference;
    }
}

// -------------------------------------------------
// TRADE MODEL
// -------------------------------------------------

static class Trade {

    public String customerId;

    public String clientName;

    public String tradeId;

    public String buySell;

    public double rate;

    public double qty;

    public String scripCode;

    public String tradeDate;

    public String tradeStatus;

    public Trade(
            String customerId,
            String clientName,
            String tradeId,
            String buySell,
            double rate,
            double qty,
            String scripCode,
            String tradeDate,
            String tradeStatus
    ) {

        this.customerId = customerId;

        this.clientName = clientName;

        this.tradeId = tradeId;

        this.buySell = buySell;

        this.rate = rate;

        this.qty = qty;

        this.scripCode = scripCode;

        this.tradeDate = tradeDate;

        this.tradeStatus = tradeStatus;
    }
}

// -------------------------------------------------
// ALERT MODEL
// -------------------------------------------------

static class Alert {

    public String caseId;

    public String customerId;

    public String clientName;

    public String severity;

    public List<String> ruleHits =
            new ArrayList<>();

    public String reason;

    public double anomalyScore;

    public double totalTransactionAmount;

    public int transactionCount;

    public Alert(
            String customerId,
            String clientName
    ) {

        this.customerId = customerId;

        this.clientName = clientName;
    }
}

// -------------------------------------------------
// HOME ENDPOINT
// -------------------------------------------------

@GetMapping("/")
public Map<String, Object> home() {

    Map<String, Object> response =
            new HashMap<>();

    response.put(
            "status",
            "RUNNING"
    );

    response.put(
            "endpoint",
            "http://localhost:8080/monitor"
    );

    response.put(
            "system",
            "Hybrid AML Monitoring Engine"
    );

    return response;
}

// -------------------------------------------------
// MAIN AML ENDPOINT
// -------------------------------------------------

@CrossOrigin(origins = "*")

@GetMapping("/monitor")
public List<Alert> monitor()
        throws Exception {

    List<Transaction> transactions =
            loadTransactions();

    Map<String, List<Transaction>> grouped =
            groupTransactions(transactions);

    List<Alert> alerts =
            new ArrayList<>();

    for (String customerId : grouped.keySet()) {

        List<Transaction> customerTransactions =
                grouped.get(customerId);

        String clientName =
                customerTransactions.get(0).clientName;

        Alert alert =
                new Alert(customerId, clientName);

        alert.caseId =
                "AML-" + System.currentTimeMillis()
                        + "-" + customerId;

        applyRules(customerTransactions, alert);

        alert.anomalyScore =
                callPythonModel(customerTransactions);

        // -----------------------------------------
        // SEVERITY
        // -----------------------------------------

        if (

                alert.anomalyScore >= 80 ||

                alert.ruleHits.size() >= 4 ||

                alert.totalTransactionAmount >= 30000000

        ) {

            alert.severity = "HIGH";
        }

        else if (

                alert.anomalyScore >= 50 ||

                alert.ruleHits.size() >= 2 ||

                alert.totalTransactionAmount >= 10000000

        ) {

            alert.severity = "MEDIUM";
        }

        else {

            alert.severity = "LOW";
        }

        // -----------------------------------------
        // REASON
        // -----------------------------------------

        if (
                !alert.ruleHits.isEmpty()
        ) {

            alert.reason =
                    String.join(
                            ", ",
                            alert.ruleHits
                    );
        }

        else {

            alert.reason =
                    "ML anomaly detection";
        }

        alerts.add(alert);
    }

    return alerts;
}

// -------------------------------------------------
// DATABASE CONNECTION
// -------------------------------------------------

private Connection getConnection()
        throws Exception {

    Class.forName(
            "org.postgresql.Driver"
    );

    return DriverManager.getConnection(
            dbUrl,
            dbUser,
            dbPassword
    );
}

// -------------------------------------------------
// LOAD TRANSACTIONS
// -------------------------------------------------

private List<Transaction> loadTransactions()
        throws Exception {

    List<Transaction> list =
            new ArrayList<>();

    Connection conn =
            getConnection();

    String sql = """
            SELECT
                customer_id,
                client_name,
                amount,
                transaction_date,
                segment,
                voucher_type,
                remarks,
                instrument_type,
                transaction_reference_number
            FROM transaction_file
            """;

    PreparedStatement stmt =
            conn.prepareStatement(sql);

    ResultSet rs =
            stmt.executeQuery();

    while (rs.next()) {

        list.add(

                new Transaction(

                        String.valueOf(
                                rs.getObject(
                                        "customer_id"
                                )
                        ),

                        rs.getString(
                                "client_name"
                        ),

                        rs.getDouble(
                                "amount"
                        ),

                        rs.getString(
                                "transaction_date"
                        ),

                        rs.getString(
                                "segment"
                        ),

                        rs.getString(
                                "voucher_type"
                        ),

                        rs.getString(
                                "remarks"
                        ),

                        rs.getString(
                                "instrument_type"
                        ),

                        rs.getString(
                                "transaction_reference_number"
                        )
                )
        );
    }

    rs.close();

    stmt.close();

    conn.close();

    return list;
}

// -------------------------------------------------
// GROUP TRANSACTIONS
// -------------------------------------------------

private Map<String, List<Transaction>>
groupTransactions(
        List<Transaction> transactions
) {

    Map<String, List<Transaction>> grouped =
            new HashMap<>();

    for (Transaction t : transactions) {

        grouped.computeIfAbsent(
                t.customerId,
                k -> new ArrayList<>()
        ).add(t);
    }

    return grouped;
}

// -------------------------------------------------
// AML RULE ENGINE
// -------------------------------------------------

private void applyRules(
        List<Transaction> txns,
        Alert alert
) throws Exception {

    double total = 0;

    int rapidMovementCount = 0;

    int structuringCount = 0;

    int highValueCount = 0;

    for (Transaction t : txns) {

        total += t.amount;

        if (
                t.amount >= 4500 &&
                t.amount <= 5000
        ) {

            structuringCount++;
        }

        if (
                t.voucherType != null &&
                t.voucherType.contains("BANK")
        ) {

            rapidMovementCount++;
        }

        if (t.amount > 200000) {

            highValueCount++;
        }
    }

    alert.totalTransactionAmount =
            total;

    alert.transactionCount =
            txns.size();

    if (total > 15000000) {

        alert.ruleHits.add(
                "LARGE_TRANSACTION_VOLUME"
        );
    }

    if (structuringCount >= 3) {

        alert.ruleHits.add(
                "STRUCTURING_PATTERN"
        );
    }

    if (rapidMovementCount >= 5) {

        alert.ruleHits.add(
                "RAPID_MOVEMENT"
        );
    }

    if (highValueCount >= 2) {

        alert.ruleHits.add(
                "MULTIPLE_HIGH_VALUE_TRANSACTIONS"
        );
    }

    List<Trade> trades =
            loadTrades(alert.customerId);

    double totalTradeValue = 0;

    for (Trade trade : trades) {

        totalTradeValue +=
                trade.rate * trade.qty;
    }

    if (
            Math.abs(
                    totalTradeValue - total
            ) > 7000000
    ) {

        alert.ruleHits.add(
                "TRADE_TRANSACTION_MISMATCH"
        );
    }
}

// -------------------------------------------------
// LOAD TRADES
// -------------------------------------------------

private List<Trade> loadTrades(
        String customerId
) throws Exception {

    List<Trade> trades =
            new ArrayList<>();

    Connection conn =
            getConnection();

    String sql = """
            SELECT
                customer_id,
                client_name,
                trade_id,
                buy_sell,
                rate,
                qty,
                scrip_code,
                trade_date,
                trade_status
            FROM trade_file
            WHERE CAST(customer_id AS TEXT) = ?
            """;

    PreparedStatement stmt =
            conn.prepareStatement(sql);

    stmt.setString(
            1,
            customerId
    );

    ResultSet rs =
            stmt.executeQuery();

    while (rs.next()) {

        trades.add(

                new Trade(

                        String.valueOf(
                                rs.getObject(
                                        "customer_id"
                                )
                        ),

                        rs.getString(
                                "client_name"
                        ),

                        rs.getString(
                                "trade_id"
                        ),

                        rs.getString(
                                "buy_sell"
                        ),

                        rs.getDouble(
                                "rate"
                        ),

                        rs.getDouble(
                                "qty"
                        ),

                        rs.getString(
                                "scrip_code"
                        ),

                        rs.getString(
                                "trade_date"
                        ),

                        rs.getString(
                                "trade_status"
                        )
                )
        );
    }

    rs.close();

    stmt.close();

    conn.close();

    return trades;
}

// -------------------------------------------------
// PYTHON ML CALL
// -------------------------------------------------

private double callPythonModel(
        List<Transaction> txns
) {

    try {

        double total = 0;

        double max = 0;

        double min =
                Double.MAX_VALUE;

        int bankCount = 0;

        for (Transaction t : txns) {

            total += t.amount;

            if (t.amount > max) {

                max = t.amount;
            }

            if (t.amount < min) {

                min = t.amount;
            }

            if (
                    t.voucherType != null &&
                    t.voucherType.contains(
                            "BANK"
                    )
            ) {

                bankCount++;
            }
        }

        int count =
                txns.size();

        double average =
                total / count;

        double cashRatio =
                (double) bankCount / count;

        double rapidRatio =
                count > 10 ? 0.8 : 0.2;

        String urlString =
                "http://ml-service:5000/predict"
                        + "?total=" + total
                        + "&count=" + count
                        + "&avg=" + average
                        + "&max=" + max
                        + "&min=" + min
                        + "&bank=" + bankCount
                        + "&cashratio="
                        + cashRatio
                        + "&rapidratio="
                        + rapidRatio;

        URL url =
                new URL(urlString);

        HttpURLConnection conn =
                (HttpURLConnection)
                        url.openConnection();

        conn.setRequestMethod(
                "GET"
        );

        BufferedReader in =
                new BufferedReader(

                        new InputStreamReader(
                                conn.getInputStream()
                        )
                );

        String response =
                in.readLine();

        in.close();

        return Double.parseDouble(
                response
        );
    }

    catch (Exception e) {

        e.printStackTrace();

        return 0;
    }
}

// -------------------------------------------------
// ERROR HANDLER
// -------------------------------------------------

@ExceptionHandler(Exception.class)

public Map<String, Object>
handleException(

        Exception e,

        HttpServletRequest request
) {

    Map<String, Object> error =
            new HashMap<>();

    error.put(
            "path",
            request.getRequestURI()
    );

    error.put(
            "error",
            e.getClass().getName()
    );

    error.put(
            "message",
            e.getMessage()
    );

    error.put(
            "status",
            500
    );

    e.printStackTrace();

    return error;
}
}
