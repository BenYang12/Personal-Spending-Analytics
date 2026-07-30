package com.ledgerlens.backend.export;

import com.ledgerlens.backend.transaction.Transaction;
import com.ledgerlens.backend.transaction.TransactionRepository;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// I need my transaction history outside the JVM so the Python pipeline in /ml
// can train on it. I export CSV rather than JSON because pandas reads CSV in
// one line, and because this keeps the boundary dead simple: the ML side never
// talks to Postgres, so it can never accidentally write to my source of truth.
//
// The direction of the arrow is the design: /ml PULLS a snapshot. My offline
// training never runs inside a request, and my API never depends on Python
// being up. That's the offline-train / online-score split I'm building toward.
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final TransactionRepository transactions;

    public ExportController(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    // I load everything into memory and hand back one String. That's honest for
    // my dataset (a few hundred rows) and I'd rather be clear than clever here.
    // If this grew to millions of rows I'd switch to StreamingResponseBody with
    // a JDBC cursor so the heap never holds the whole export — I'm noting the
    // upgrade path deliberately rather than pretending this scales.
    @GetMapping(value = "/transactions.csv", produces = "text/csv")
    public ResponseEntity<String> exportTransactions() {
        List<Transaction> all = transactions.findAll();

        StringBuilder csv = new StringBuilder();
        // I write an explicit header row so the Python side can read columns by
        // NAME. If I relied on position, adding a column here would silently
        // shift every feature in my pipeline — a genuinely nasty class of bug.
        csv.append("transaction_id,account_id,posted_date,amount,merchant,category,pending\n");

        for (Transaction t : all) {
            csv.append(escape(t.getPlaidTransactionId())).append(',')
                    .append(t.getAccountId()).append(',')
                    .append(t.getPostedDate()).append(',')
                    // BigDecimal.toString gives me the exact decimal I stored.
                    // I never format money through a double on the way out.
                    .append(t.getAmount()).append(',')
                    .append(escape(t.getMerchant())).append(',')
                    .append(escape(t.getCategory())).append(',')
                    .append(t.isPending()).append('\n');
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                // Content-Disposition makes a browser download this rather than
                // render it, which is what I want from a manual sanity check.
                .header("Content-Disposition", "attachment; filename=\"transactions.csv\"")
                .body(csv.toString());
    }

    // Merchant names contain commas ("Cookout, Inc") and occasionally quotes.
    // Un-escaped, one comma silently shifts every later column on that row, so
    // I follow RFC 4180: wrap in quotes, and double any embedded quote.
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
