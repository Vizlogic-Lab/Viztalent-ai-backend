package com.smartstaff.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** One question (+ eventual answer) within an Interview, in `seq` order.
 *  Created empty-answered at prepare time; save/saveByToken replace every
 *  turn for the interview wholesale rather than patching by index — same
 *  "delete and recreate" pattern ScreeningServiceImpl and
 *  AssessmentServiceImpl already use elsewhere in this codebase. */
@Entity
@Table(name = "interview_turns")
@Getter
@Setter
@NoArgsConstructor
public class InterviewTurn {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interview_id", nullable = false)
    private Interview interview;

    private int seq;

    private String category;

    private String skill;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer = "";
}
