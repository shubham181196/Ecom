package com.ecom.controller;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ecom.config.SecurityConfig;
import com.ecom.model.*;
import com.ecom.service.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.Session;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.ecom.repository.UserRepository;
import com.ecom.util.CommonUtil;
import com.ecom.util.OrderStatus;

import jakarta.servlet.http.HttpSession;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Controller
@RequestMapping("/user")
public class UserController {
	@Value("${RZPS}")
	private String SECRET;
	@Autowired
	private UserService userService;
	@Autowired
	private CategoryService categoryService;

	@Autowired
	private CartService cartService;

	@Autowired
	private OrderService orderService;

	@Autowired
	private CommonUtil commonUtil;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private ProductService productService;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private SecurityConfig securityConfig;
	@GetMapping("/")
	public String home() {
		return "user/home";
	}

	@ModelAttribute
	public void getUserDetails(Principal p, Model m) {
		if (p != null) {
			String email = p.getName();
			UserDtls userDtls = userService.getUserByEmail(email);
			m.addAttribute("user", userDtls);
			Integer countCart = cartService.getCountCart(userDtls.getId());
			m.addAttribute("countCart", countCart);
		}

		List<Category> allActiveCategory = categoryService.getAllActiveCategory();
		m.addAttribute("categorys", allActiveCategory);
	}

	@GetMapping("/addCart")
	public String addToCart(@RequestParam(name="pid") Integer pid, @RequestParam(name="uid") Integer uid, HttpSession session) {

		Cart saveCart = cartService.saveCart(pid, uid);

		if (ObjectUtils.isEmpty(saveCart)) {
			session.setAttribute("errorMsg", "Product add to cart failed");
		} else {
			session.setAttribute("succMsg", "Product added to cart");
		}
		return "redirect:/product/" + pid;
	}

	@GetMapping("/create-order")
	public String createOrder(Principal principal, Model model) throws Exception {


		// Initialize RestTemplate with authentication
		RestTemplate restTemplate = securityConfig.getRestTemplate();
		restTemplate.getInterceptors().add(new BasicAuthenticationInterceptor("rzp_test_zEaIxcKws1Lycn", SECRET));

		// Prepare the request body for Razorpay API
		Map<String, Object> requestBody = new HashMap<>();
		requestBody.put("amount", 100); // Set amount in the smallest currency unit (paise)
		requestBody.put("currency", "INR");
		requestBody.put("receipt", "#trn_36");

		// Set up HTTP headers
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

		try {
			// Call Razorpay API to create an order
			ResponseEntity<Map> response = restTemplate.exchange(
					"https://api.razorpay.com/v1/orders",
					HttpMethod.POST,
					requestEntity,
					Map.class
			);

			// Handle the successful response
			System.out.println(response.getStatusCode() + " :: " + response.getBody().get("amount").toString());
			model.addAttribute("orderId", response.getBody().get("id"));
			model.addAttribute("name", "shubham"); // Replace with actual user name
			model.addAttribute("amount", "400"); // Set the correct amount
			model.addAttribute("email", "shubham@gmail.com"); // Replace with actual user email
			model.addAttribute("contact", "9023600835"); // Replace with actual user contact

			return "/user/paymentgateway"; // Redirect to the payment gateway view
		} catch (Exception e) {
			e.printStackTrace(); // Log the exception for debugging
		}

		// Redirect to the cart page in case of an error
		return "redirect:/user/cart";
	}


	@GetMapping("/cart")
	public String loadCartPage(Principal p, Model m) {

		UserDtls user = getLoggedInUserDetails(p);
		List<Cart> carts = cartService.getCartsByUser(user.getId());
		m.addAttribute("carts", carts);
		if (carts.size() > 0) {
			Double totalOrderPrice = carts.get(carts.size() - 1).getTotalOrderPrice();
			m.addAttribute("totalOrderPrice", totalOrderPrice);
		}
		return "/user/cart";
	}


	@GetMapping("/cartQuantityUpdate")
	public String updateCartQuantity(@RequestParam(name = "sy") String sy, @RequestParam(name = "cid") Integer cid) {
		cartService.updateQuantity(sy, cid);
		return "redirect:/user/cart";
	}

	private UserDtls getLoggedInUserDetails(Principal p) {
		String email = p.getName();
		UserDtls userDtls = userService.getUserByEmail(email);
		return userDtls;
	}

	@GetMapping("/orders")
	public String orderPage(Principal p, Model m) {
		UserDtls user = getLoggedInUserDetails(p);
		List<Cart> carts = cartService.getCartsByUser(user.getId());
		m.addAttribute("carts", carts);
		if (carts.size() > 0) {
			Double orderPrice = carts.get(carts.size() - 1).getTotalOrderPrice();
			Double totalOrderPrice = carts.get(carts.size() - 1).getTotalOrderPrice() + 250 + 100;
			m.addAttribute("orderPrice", orderPrice);
			m.addAttribute("totalOrderPrice", totalOrderPrice);
		}

		return "/user/order";
	}

	@PostMapping("/save-order")
	public String saveOrder(@ModelAttribute OrderRequest request, Principal p) throws Exception {
		// System.out.println(request);
		UserDtls user = getLoggedInUserDetails(p);
		orderService.saveOrder(user.getId(), request);

		return "redirect:/user/success";
	}

	@PostMapping("/success")
	public String loadSuccess(Principal principal, @RequestParam("razorpay_payment_id") String paymentId,
							   @RequestParam("razorpay_order_id") String orderId,
							   @RequestParam("razorpay_signature") String signature) throws Exception {

		boolean isValidSignature = verifySignature(paymentId, orderId, signature);

		if (isValidSignature) {
			Boolean paymentStatus = queryPaymentStatus(paymentId);
			if (paymentStatus.equals(Boolean.TRUE)) {
				UserDtls user = getLoggedInUserDetails(principal);
				Integer userId=user.getId();
				List<Cart>carts=cartService.getCartsByUser(userId);

				for(Cart a:carts){
					Product orderedProduct=productService.getProductById(a.getProduct().getId());
					Integer qty=orderedProduct.getStock();
					if(a.getQuantity()>qty){
						return "login";
					}else{
						qty-=a.getQuantity();
						orderedProduct.setStock(qty);
						productService.updateProduct(orderedProduct);
						OrderRequest or=new OrderRequest();
						or.setFirstName(user.getName());
						or.setLastName(user.getName());
						or.setEmail(user.getEmail());
						or.setMobileNo(user.getMobileNumber());
						or.setCity(user.getCity());
						or.setAddress(user.getAddress());
						or.setState(user.getState());
						orderService.saveOrder(userId,or);
						cartService.deletecart(a);

					}
				}
				return "/user/success";
			}
			else return "login";
		} else return "login";


	}
	private boolean verifySignature(String paymentId, String orderId, String signature) throws NoSuchAlgorithmException, InvalidKeyException {
		String substrate=orderId+"|"+paymentId;
		Mac mac = Mac.getInstance("HmacSHA256");
		SecretKeySpec secretKeySpec = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		mac.init(secretKeySpec);
		byte[] hash = mac.doFinal(substrate.getBytes(StandardCharsets.UTF_8));
		String generatedSignature = new String(Hex.encodeHex(hash));
		if(generatedSignature.equals(signature))return true;
		else return false;


	}


	private Boolean queryPaymentStatus(String paymentId) {

		RestTemplate rs=securityConfig.getRestTemplate();
		ResponseEntity<Map>map=rs.getForEntity("https://api.razorpay.com/v1/payments/"+paymentId,Map.class);
		Boolean status= (Boolean) map.getBody().get("captured");
		System.out.println(map.getBody().get("captured"));
		System.out.println(map.getBody().toString());
		return status;
	}

	@GetMapping("/user-orders")
	public String myOrder(Model m, Principal p) {
		UserDtls loginUser = getLoggedInUserDetails(p);
		List<ProductOrder> orders = orderService.getOrdersByUser(loginUser.getId());
		m.addAttribute("orders", orders);
		return "/user/my_orders";
	}

	@GetMapping("/update-status")
	public String updateOrderStatus(@RequestParam Integer id, @RequestParam Integer st, HttpSession session) {

		OrderStatus[] values = OrderStatus.values();
		String status = null;

		for (OrderStatus orderSt : values) {
			if (orderSt.getId().equals(st)) {
				status = orderSt.getName();
			}
		}

		ProductOrder updateOrder = orderService.updateOrderStatus(id, status);
		
		try {
			commonUtil.sendMailForProductOrder(updateOrder, status);
		} catch (Exception e) {
			e.printStackTrace();
		}

		if (!ObjectUtils.isEmpty(updateOrder)) {
			session.setAttribute("succMsg", "Status Updated");
		} else {
			session.setAttribute("errorMsg", "status not updated");
		}
		return "redirect:/user/user-orders";
	}

	@GetMapping("/profile")
	public String profile() {
		return "/user/profile";
	}

	@PostMapping("/update-profile")
	public String updateProfile(@ModelAttribute UserDtls user, @RequestParam MultipartFile img, HttpSession session) {
		UserDtls updateUserProfile = userService.updateUserProfile(user, img);
		if (ObjectUtils.isEmpty(updateUserProfile)) {
			session.setAttribute("errorMsg", "Profile not updated");
		} else {
			session.setAttribute("succMsg", "Profile Updated");
		}
		return "redirect:/user/profile";
	}

	@PostMapping("/change-password")
	public String changePassword(@RequestParam String newPassword, @RequestParam String currentPassword, Principal p,
			HttpSession session) {
		UserDtls loggedInUserDetails = getLoggedInUserDetails(p);

		boolean matches = passwordEncoder.matches(currentPassword, loggedInUserDetails.getPassword());

		if (matches) {
			String encodePassword = passwordEncoder.encode(newPassword);
			loggedInUserDetails.setPassword(encodePassword);
			UserDtls updateUser = userService.updateUser(loggedInUserDetails);
			if (ObjectUtils.isEmpty(updateUser)) {
				session.setAttribute("errorMsg", "Password not updated !! Error in server");
			} else {
				session.setAttribute("succMsg", "Password Updated sucessfully");
			}
		} else {
			session.setAttribute("errorMsg", "Current Password incorrect");
		}

		return "redirect:/user/profile";
	}

}
