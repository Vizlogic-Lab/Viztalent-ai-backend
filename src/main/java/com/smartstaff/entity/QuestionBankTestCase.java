package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** stdin/stdout test of a bank coding question; copied into
 *  question_test_cases when the item is used in an assessment. */
@Entity
@Table(name = "question_bank_test_cases")
@Getter
@Setter
@NoArgsConstructor
public class QuestionBankTestCase {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private QuestionBankItem item;

    private int seq;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String input = "";

    @Column(name = "expected_output", nullable = false, columnDefinition = "TEXT")
    private String expectedOutput;

    private boolean visible;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private TestCaseCategory category = TestCaseCategory.BASIC;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal weight = BigDecimal.ONE;

    @Column(name = "float_tolerance")
    private BigDecimal floatTolerance;
}
