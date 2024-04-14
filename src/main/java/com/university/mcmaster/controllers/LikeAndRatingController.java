package com.university.mcmaster.controllers;

import com.university.mcmaster.repositories.LikeAndRatingRepo;
import com.university.mcmaster.services.LikeAndRatingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LikeAndRatingController {

    private final LikeAndRatingService likeAndRatingService;

    @PostMapping("/like/rental-unit/{rentalUnitId}")
    public ResponseEntity<?> likeRentalUnit(
            @PathVariable("rentalUnitId") String rentalUnitId,
            @RequestParam(name = "like",required = false,defaultValue = "true") boolean like,
            @RequestHeader("requestId")String requestId,
            HttpServletRequest request
    ){
        return likeAndRatingService.likeRentalUnit(rentalUnitId,like,requestId,request);
    }

    @PostMapping("/rating/rental-unit/{rentalUnitId}")
    public ResponseEntity<?> rateRentalUnit(
            @PathVariable("rentalUnitId") String rentalUnitId,
            @RequestParam(name = "star",required = false,defaultValue = "3") int star,
            @RequestHeader("requestId")String requestId,
            HttpServletRequest request
    ){
        return likeAndRatingService.rateRentalUnit(rentalUnitId,star,requestId,request);
    }

}
