package com.example.portfolio_service.model;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "positions", indexes = {
        @Index(name = "idx_positions_user_sub", columnList = "userSub")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** JWT "sub" of owner */
    @Column(nullable = false, length = 64)
    private String userSub;


    @Column(nullable = false, length = 32)
    private String ticker;


    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal buyPrice;

    private LocalDate buyDate;

    @Column(length = 512)
    private String notes;
}
