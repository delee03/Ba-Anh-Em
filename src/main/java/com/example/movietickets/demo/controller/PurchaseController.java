package com.example.movietickets.demo.controller;

import com.example.movietickets.demo.model.*;
import com.example.movietickets.demo.repository.RoomRepository;
import com.example.movietickets.demo.repository.SeatRepository;
import com.example.movietickets.demo.repository.UserRepository;
import com.example.movietickets.demo.service.*;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.security.core.Authentication;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;

@Controller
@AllArgsConstructor
@RequestMapping("/purchase")
public class PurchaseController {
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

    @GetMapping
    public String showPurchase(Model model, @RequestParam(required = false) Long scheduleId) {
        if (purchaseService.IsExist()) {
            Purchase purchase = purchaseService.Get();
            System.out.println("scheduleId: " + scheduleId);
            System.out.println("selectedSeats: " + purchase.getSeats());
            model.addAttribute("selectedSeats", purchase.getSeats());
            model.addAttribute("filmTitle", purchase.getFilmTitle());
            model.addAttribute("category", purchase.getCategory());
            model.addAttribute("cinemaName", purchase.getCinemaName());
            model.addAttribute("cinemaAddress", purchase.getCinemaAddress());
            model.addAttribute("startTime", purchase.getStartTime());
            model.addAttribute("roomName", purchase.getRoomName());
            model.addAttribute("poster", purchase.getPoster());
            //format Currency VND
            NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("vi", "VN"));
            String formattedTotalPrice = currencyFormat.format(purchase.getTotalPrice());
            model.addAttribute("totalPrice", formattedTotalPrice);

            List<ComboFood> comboFoods = comboFoodService.getAllComboFood();
            Room room = roomRepository.findByName(purchase.getRoomName());
            List<Seat> seats = seatRepository.findByRoom(room);
            //add thêm trn header
            List<Category> categories = categoryService.getAllCategories();
            model.addAttribute("categories", categories);
            model.addAttribute("comboFoods", comboFoods);
            //lấy ra các seat booked
            model.addAttribute("purchase", purchase);
            model.addAttribute("seats", seats);
            model.addAttribute("scheduleId", scheduleId);
        }
        return "/purchase/purchase";
    }



    @GetMapping("/clear")
    public String clearPurchase() {
        purchaseService.clearPurchase();
        return "redirect:/purchase";
    }


    @PostMapping("/add")
    public String addPurchase(
            @RequestParam("seatSymbol") String seatSymbols,
            @RequestParam("totalPrice") Long totalPrice,
            @RequestParam("startTime") String startTime,
            @RequestParam("filmTitle") String filmTitle,
            @RequestParam("poster") String poster,
            @RequestParam("category") String category,
            @RequestParam("cinemaName") String cinemaName,
            @RequestParam("cinemaAddress") String cinemaAddress,
            @RequestParam("roomName") String roomName,
            @RequestParam("scheduleId") Long scheduleId,
            Model model
    ) {
        System.out.println("scheduleId in addPurchase: " + scheduleId); // Debugging
        purchaseService.addToBuy(seatSymbols, filmTitle, poster, category, totalPrice, cinemaAddress, cinemaName, startTime, roomName);
        model.addAttribute("scheduleId", scheduleId);
        return "redirect:/purchase?scheduleId=" + scheduleId;
    }

    @GetMapping("/history")
    public String showPurchaseHistory(Model model) {
        List<Booking> bookings = bookingService.getBookingsByCurrentUser(); // phương thức này để lấy các booking của người dùng hiện tại
        List<Category> categories = categoryService.getAllCategories();
        model.addAttribute("categories", categories);
        model.addAttribute("bookings", bookings);
        return "/purchase/history";
    }

    @PostMapping("/checkout")
    public String checkout(
            @RequestParam("payment") String payment,
            @RequestParam String comboId, //nhận String từ form purrchase
            @RequestParam Long scheduleId,
            RedirectAttributes redirectAttributes
    ) {
        if (purchaseService.IsExist()) {
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
            booking.setPayment(payment);
            booking.setStatus(true); // Hoặc giá trị khác tùy vào logic của bạn
            booking.setCreateAt(new Date());
            booking.setPrice(purchase.getTotalPrice()+ comboPrice); //cộng thêm giá từ food

            if (comboFoodId != null) {
                ComboFood comboFood = comboFoodService.getComboFoodById(comboFoodId).orElseThrow(() -> new EntityNotFoundException("Combo not found"));
                booking.setComboFood(comboFood);
            }

            // Kiểm tra phương thức thanh toán
            if ("vnpay".equalsIgnoreCase(payment)) {
                //return "redirect:/api/payment/create_payment?amount=" + purchase.getTotalPrice();
                return "redirect:/api/payment/create_payment?scheduleId=" + scheduleId + "&amount="  + booking.getPrice() + "&comboId="  + comboId ;
            }

            // Lấy thông tin người dùng hiện tại
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            User user = getUserFromAuthentication(authentication);
            booking.setUser(user);

            bookingService.saveBooking(booking, seats, schedule);

            redirectAttributes.addFlashAttribute("message", "Đặt vé thành công!");
        } else {
            redirectAttributes.addFlashAttribute("message", "Không có thông tin đặt vé.");
        }
        return "redirect:/purchase/history"; // Chuyển hướng đến trang lịch sử mua vé
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


    private Date parseDate(String dateStr) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return dateFormat.parse(dateStr);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
