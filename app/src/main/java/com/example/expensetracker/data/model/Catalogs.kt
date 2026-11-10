package com.example.expensetracker.data.model

/** Fixed category list used by the tracker. Stored in Room as the stable [key]. */
enum class ExpenseCategory(val key: String, val label: String) {
    FOOD("FOOD", "Food"),
    TRANSPORT("TRANSPORT", "Transport"),
    SHOPPING("SHOPPING", "Shopping"),
    EDUCATION("EDUCATION", "Education"),
    OTHER("OTHER", "Other"),
    ;

    companion object {
        val keys: List<String> = entries.map { it.key }

        fun fromKey(key: String): ExpenseCategory =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: OTHER

        fun label(key: String): String = fromKey(key).label
    }
}

/** Predefined payment methods offered on the add/edit screen. */
enum class PaymentMethod(val key: String, val label: String) {
    CASH("CASH", "Cash"),
    UPI("UPI", "UPI"),
    DEBIT_CARD("DEBIT_CARD", "Debit Card"),
    CREDIT_CARD("CREDIT_CARD", "Credit Card"),
    NET_BANKING("NET_BANKING", "Net Banking"),
    ;

    companion object {
        val keys: List<String> = entries.map { it.key }

        fun fromKey(key: String): PaymentMethod? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }

        fun label(key: String): String = fromKey(key)?.label ?: key
    }
}
