package com.example.movietickets.demo.controller;


import com.example.movietickets.demo.DTO.PaymentResDTO;
import com.example.movietickets.demo.config.Config;
import com.example.movietickets.demo.model.*;
import com.example.movietickets.demo.repository.RoomRepository;
import com.example.movietickets.demo.repository.SeatRepository;
import com.example.movietickets.demo.repository.UserRepository;
import com.example.movietickets.demo.service.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.web.bind.annotation.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ScheduleServiceImpl scheduleService;

    @Autowired
    private ComboFoodService comboFoodService;

    @Autowired
    private CategoryService categoryService;


    @GetMapping("create_payment")
    public ResponseEntity<?> createPayment(@RequestParam("amount") long amount, @RequestParam("scheduleId") Long scheduleId, @RequestParam("comboId") String comboId) throws UnsupportedEncodingException {

        // Kiểm tra giá trị amount
        System.out.println("Amount received: " + amount);

        //String orderType = "other";
        //long amount = Integer.parseInt(req.getParameter("amount"))*100;
        //String bankCode = req.getParameter("bankCode");

        //long amount = 10000;
        String amountValue = String.valueOf(amount*100);
        String vnp_TxnRef = Config.getRandomNumber(8);
        //String vnp_IpAddr = Config.getIpAddress(req);
        String vnp_TmnCode = Config.vnp_TmnCode;

        Map<String, String> vnp_Params = new HashMap<>();
        vnp_Params.put("vnp_Version", Config.vnp_Version);
        vnp_Params.put("vnp_Command", Config.vnp_Command);
        vnp_Params.put("vnp_TmnCode", vnp_TmnCode);
        vnp_Params.put("vnp_Amount", amountValue);
        vnp_Params.put("vnp_CurrCode", "VND");
        vnp_Params.put("vnp_BankCode", "NCB");
        vnp_Params.put("vnp_Locale", "vn");
        //vnp_Params.put("vnp_TransactionNo", "NCB");
        vnp_Params.put("vnp_IpAddr", Config.vnp_IpAddr);

        vnp_Params.put("vnp_TxnRef", vnp_TxnRef);
        vnp_Params.put("vnp_OrderInfo", "Thanh toan don hang:" + vnp_TxnRef);
        vnp_Params.put("vnp_OrderType", "other" );
        vnp_Params.put("vnp_ReturnUrl", Config.vnp_ReturnUrl);


        Calendar cld = Calendar.getInstance(TimeZone.getTimeZone("Etc/GMT+7"));

        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
        String vnp_CreateDate = formatter.format(cld.getTime());
        vnp_Params.put("vnp_CreateDate", vnp_CreateDate);

        cld.add(Calendar.MINUTE, 5);
        String vnp_ExpireDate = formatter.format(cld.getTime());
        vnp_Params.put("vnp_ExpireDate", vnp_ExpireDate);

        List fieldNames = new ArrayList(vnp_Params.keySet());
        Collections.sort(fieldNames);
        StringBuilder hashData = new StringBuilder();
        StringBuilder query = new StringBuilder();
        Iterator itr = fieldNames.iterator();
        while (itr.hasNext()) {
            String fieldName = (String) itr.next();
            String fieldValue = (String) vnp_Params.get(fieldName);
            if ((fieldValue != null) && (fieldValue.length() > 0)) {
                //Build hash data
                hashData.append(fieldName);
                hashData.append('=');
                hashData.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                //Build query
                query.append(URLEncoder.encode(fieldName, StandardCharsets.US_ASCII.toString()));
                query.append('=');
                query.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                if (itr.hasNext()) {
                    query.append('&');
                    hashData.append('&');
                }
            }
        }

        String queryUrl = query.toString();
        String vnp_SecureHash = Config.hmacSHA512(Config.secretKey, hashData.toString());
        vnp_Params.put("vnp_SecureHash", vnp_SecureHash);
        queryUrl += "&vnp_SecureHash=" + vnp_SecureHash;
        String paymentUrl = Config.vnp_PayUrl + "?" + queryUrl;

        PaymentResDTO paymentResDTO = new PaymentResDTO();
        paymentResDTO.setStatus("OK");
        paymentResDTO.setMessage("Successfully");
        paymentResDTO.setUrl(paymentUrl);

        Purchase purchase = purchaseService.Get();
        List<String> seatSymbols = new ArrayList<>();
        for (Purchase.Seat2 seat : purchase.getSeatsList()) {
            seatSymbols.add(seat.getSymbol());
        }

        Room room = roomRepository.findByName(purchase.getRoomName());
        List<Seat> seats = bookingService.getSeatsFromSymbolsAndRoom(seatSymbols, room);

        // Lấy schedule từ scheduleId
        Schedule schedule = scheduleService.getScheduleById(scheduleId).orElseThrow(() -> new IllegalArgumentException("Invalid schedule Id"));
            // Tách comboId và comboPrice từ giá trị của request parameter
            Long comboFoodId = null;
            Long comboPrice = 0L;
            if (!comboId.equals("0-0")) { // Kiểm tra xem có chọn combo hay không
                String[] comboDetails = comboId.split("-");
                comboFoodId = Long.parseLong(comboDetails[0]);
                comboPrice = Long.parseLong(comboDetails[1]);
            }


        Booking booking = new Booking();
        booking.setFilmName(purchase.getFilmTitle());
        booking.setPoster(purchase.getPoster());
        booking.setCinemaName(purchase.getCinemaName());
        booking.setCinemaAddress(purchase.getCinemaAddress());
        booking.setStartTime(parseDate(purchase.getStartTime()));
        booking.setSeatName(purchase.getSeats());
        booking.setRoomName(purchase.getRoomName());
        booking.setPayment("vnpay");
        booking.setStatus(true); // Hoặc giá trị khác tùy vào logic của bạn
        booking.setCreateAt(new Date());
        booking.setPrice(purchase.getTotalPrice()+ comboPrice); //cộng thêm giá từ food

        if (comboFoodId != null) {
            ComboFood comboFood = comboFoodService.getComboFoodById(comboFoodId).orElseThrow(() -> new EntityNotFoundException("Combo not found"));
            booking.setComboFood(comboFood);
        }


        // Lấy thông tin người dùng hiện tại
        // Lấy thông tin người dùng hiện tại
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User user = getUserFromAuthentication(authentication);
        booking.setUser(user);


        bookingService.saveBooking(booking, seats, schedule);

        // Trả về trang HTML tự động chuyển hướng
        String htmlResponse = "<html><body>"
                + "<form id='paymentForm' action='" + paymentUrl + "' method='GET'></form>"
                + "<script type='text/javascript'>document.getElementById('paymentForm').submit();</script>"
                + "</body></html>";

        // return ResponseEntity.status(HttpStatus.OK).body(htmlResponse);

        //return ResponseEntity.status(HttpStatus.OK).body(paymentResDTO);

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(paymentUrl));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);

        //return paymentUrl;

    }

    private Date parseDate(String dateStr) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return dateFormat.parse(dateStr);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
    private User getUserFromAuthentication(Authentication authentication) {
        if (authentication.getPrincipal() instanceof org.springframework.security.core.userdetails.User) {
            // Trường hợp đăng nhập thông thường
            String username = ((org.springframework.security.core.userdetails.User) authentication.getPrincipal()).getUsername();
            return userRepository.findByUsername(username).orElseThrow(() -> new UsernameNotFoundException("User not found"));
        } else if (authentication.getPrincipal() instanceof DefaultOAuth2User) {
            // Trường hợp đăng nhập bằng OAuth2 (Facebook, Google)
            String email = ((DefaultOAuth2User) authentication.getPrincipal()).getAttribute("email");
            return userRepository.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("User not found"));
        }
        throw new UsernameNotFoundException("User not found");
    }


//    @GetMapping("/payment_infor")
//    public String transaction(@RequestParam(value = "vnp_amount") String amount,
//                              @RequestParam(value = "vnp_BankCode") String bankcode,
//                              @RequestParam(value = "vnp_OrderInfo") String order,
//                              @RequestParam(value = "vnp_ResponseCode") String responseCode){
//
//        if(responseCode == "00"){
//            return "/transaction/transaction-success";
//        }
//
//        return "/transaction/transaction-error";
//    }
}