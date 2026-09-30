package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** stdin/stdout test for a coding question. Hidden tests (visible=false)
 *  are read only by grading code and the HR answer key. */
@Entity
@Table(name = "question_test_cases")
@Getter
@Setter
@NoArgsConstructor
public class QuestionTestCase {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private AssessmentQuestion question;

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
