package com.hotelvista.aiconcierge.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class HotelTools {

    private final RestTemplate restTemplate;

    @Value("${gateway.service.url:http://localhost:8080}")
    private String gatewayUrl;

    @Value("${booking.service.url:http://localhost:8083}")
    private String bookingServiceUrl;

    @Value("${room.service.url:http://localhost:8081}")
    private String roomServiceUrl;

    public HotelTools(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Lấy danh sách loại phòng từ room-service qua gateway
     * Trả về JSON string để AI phân tích, và List<Map> để render UI
     */
    public String getRoomTypes() {
        try {
            String url = gatewayUrl + "/api/room-types";
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null
                    && !response.getBody().isEmpty()) {
                StringBuilder sb = new StringBuilder("DANH SÁCH LOẠI PHÒNG HIỆN CÓ:\n\n");
                for (Map<String, Object> room : response.getBody()) {
                    sb.append("- ").append(room.getOrDefault("typeName", "Phòng")).append("\n");
                    sb.append("  ID: ").append(room.getOrDefault("roomTypeID", "N/A")).append("\n");
                    sb.append("  Giá: ").append(room.getOrDefault("basePrice", "N/A")).append(" VND/đêm\n");
                    sb.append("  Sức chứa: ").append(room.getOrDefault("maxOccupancy", "N/A")).append(" khách\n");
                    sb.append("  Diện tích: ").append(room.getOrDefault("area", "N/A")).append("m²\n");
                    sb.append("  Mô tả: ").append(room.getOrDefault("description", "")).append("\n\n");
                }
                log.debug("Fetched {} room types from gateway", response.getBody().size());
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("Could not fetch room types from gateway ({}), using fallback", e.getMessage());
        }
        return getFallbackRoomTypes();
    }

    /**
     * Lấy raw JSON danh sách phòng để render UI grid
     */
    public List<Map<String, Object>> getRoomTypesRaw() {
        try {
            String url = gatewayUrl + "/api/room-types";
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
        } catch (Exception e) {
            log.warn("Could not fetch raw room types: {}", e.getMessage());
        }
        return List.of(
            Map.of("roomTypeID", "RT001", "typeName", "Standard Room", "basePrice", 1200000,
                   "maxOccupancy", 2, "area", 30, "description", "Phòng tiêu chuẩn view thành phố"),
            Map.of("roomTypeID", "RT002", "typeName", "Deluxe Room", "basePrice", 1800000,
                   "maxOccupancy", 2, "area", 40, "description", "Phòng deluxe view biển một phần"),
            Map.of("roomTypeID", "RT003", "typeName", "Superior Suite", "basePrice", 2500000,
                   "maxOccupancy", 3, "area", 55, "description", "Suite cao cấp view toàn cảnh"),
            Map.of("roomTypeID", "RT004", "typeName", "Junior Suite", "basePrice", 3200000,
                   "maxOccupancy", 3, "area", 65, "description", "Suite jacuzzi, dịch vụ butler"),
            Map.of("roomTypeID", "RT005", "typeName", "Presidential Suite", "basePrice", 6000000,
                   "maxOccupancy", 4, "area", 120, "description", "2 phòng ngủ, hồ bơi riêng, butler 24/7")
        );
    }

    /**
     * Lấy danh sách phòng theo loại phòng (raw, có roomNumber)
     */
    public List<Map<String, Object>> getRoomsByTypeRaw(String roomTypeId) {
        try {
            String url = gatewayUrl + "/api/rooms?roomTypeId=" + roomTypeId;
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
        } catch (Exception e) {
            log.warn("Could not fetch rooms by type {}: {}", roomTypeId, e.getMessage());
        }
        return List.of();
    }

    /**
     * Kiểm tra tình trạng phòng trống theo ngày
     */
    public String checkRoomAvailability(String checkIn, String checkOut, int guests) {
        try {
            String url = String.format(
                    "%s/api/rooms/available?checkIn=%s&checkOut=%s&guests=%d",
                    gatewayUrl, checkIn, checkOut, guests
            );
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> available = response.getBody();
                if (available.isEmpty()) {
                    return String.format("Không có phòng trống từ %s đến %s cho %d khách.", checkIn, checkOut, guests);
                }
                StringBuilder sb = new StringBuilder(
                        String.format("Phòng trống từ %s đến %s cho %d khách:\n\n", checkIn, checkOut, guests));
                for (Map<String, Object> room : available) {
                    sb.append("- ").append(room.getOrDefault("typeName", "Phòng")).append("\n");
                    sb.append("  Số phòng: ").append(room.getOrDefault("roomNumber", "N/A")).append("\n");
                    sb.append("  Giá: ").append(room.getOrDefault("basePrice", "N/A")).append(" VND/đêm\n\n");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("Could not check availability: {}", e.getMessage());
        }
        return String.format(
                "Tình trạng phòng từ %s đến %s (%d khách): Vui lòng liên hệ lễ tân để kiểm tra phòng trống thực tế.",
                checkIn != null ? checkIn : "ngày yêu cầu",
                checkOut != null ? checkOut : "ngày yêu cầu",
                guests > 0 ? guests : 2
        );
    }

    /**
     * Lấy danh sách dịch vụ từ service-service qua gateway
     */
    public String getServicesInfo() {
        try {
            String url = gatewayUrl + "/api/services";
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null
                    && !response.getBody().isEmpty()) {
                StringBuilder sb = new StringBuilder("DỊCH VỤ KHÁCH SẠN:\n\n");
                for (Map<String, Object> svc : response.getBody()) {
                    sb.append("- ").append(svc.getOrDefault("serviceName", "Dịch vụ")).append("\n");
                    sb.append("  ID: ").append(svc.getOrDefault("serviceID", "N/A")).append("\n");
                    sb.append("  Giá: ").append(svc.getOrDefault("price", "N/A")).append(" VND\n");
                    sb.append("  Mô tả: ").append(svc.getOrDefault("description", "")).append("\n\n");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("Could not fetch services from gateway: {}", e.getMessage());
        }
        return getFallbackServices();
    }

    /**
     * Lấy raw danh sách dịch vụ để render UI
     */
    public List<Map<String, Object>> getServicesRaw() {
        try {
            String url = gatewayUrl + "/api/services";
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
        } catch (Exception e) {
            log.warn("Could not fetch raw services: {}", e.getMessage());
        }
        return List.of(
            Map.of("serviceID", "SVC001", "serviceName", "Bia Sài Gòn", "price", 500000, "description", "Xe 7 chỗ cao cấp"),
            Map.of("serviceID", "SVC002", "serviceName", "Spa & Massage", "price", 800000, "description", "Swedish Massage 60 phút"),
            Map.of("serviceID", "SVC003", "serviceName", "Giặt ủi", "price", 100000, "description", "Dịch vụ giặt ủi express"),
            Map.of("serviceID", "SVC004", "serviceName", "Bể bơi", "price", 200000, "description", "Truy cập gym và bể bơi"),
            Map.of("serviceID", "SVC005", "serviceName", "Bữa sáng buffet", "price", 350000, "description", "Buffet sáng tại nhà hàng Vista")
        );
    }

    /**
     * Lấy thông tin hóa đơn/booking theo customerId
     */
    public String getCustomerBookings(String customerId) {
        try {
            String url = gatewayUrl + "/api/bookings/customer/" + customerId;
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> bookings = response.getBody();
                if (bookings.isEmpty()) {
                    return "Bạn chưa có đặt phòng nào trong hệ thống.";
                }
                StringBuilder sb = new StringBuilder("LỊCH SỬ ĐẶT PHÒNG CỦA BẠN:\n\n");
                for (Map<String, Object> booking : bookings) {
                    sb.append("   Mã đặt phòng: ").append(booking.getOrDefault("bookingID", "N/A")).append("\n");
                    sb.append("   Ngày nhận: ").append(booking.getOrDefault("checkInDate", "N/A")).append("\n");
                    sb.append("   Ngày trả: ").append(booking.getOrDefault("checkOutDate", "N/A")).append("\n");
                    sb.append("   Trạng thái: ").append(booking.getOrDefault("status", "N/A")).append("\n");
                    sb.append("   Tổng tiền: ").append(booking.getOrDefault("totalAmount", "N/A")).append(" VND\n");
                    sb.append("   Thanh toán: ").append(booking.getOrDefault("paymentStatus", "N/A")).append("\n\n");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("Could not fetch bookings for customer {}: {}", customerId, e.getMessage());
        }
        return "Không thể tải thông tin đặt phòng. Vui lòng thử lại sau.";
    }

    /**
     * Lấy raw bookings của customer để render UI
     */
    public List<Map<String, Object>> getCustomerBookingsRaw(String customerId) {
        try {
            String url = gatewayUrl + "/api/bookings/customer/" + customerId;
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
        } catch (Exception e) {
            log.warn("Could not fetch raw bookings for customer: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * Lấy tiện ích khách sạn (hardcode, ít thay đổi)
     */
    public String getAmenities() {
        return """
                TIỆN ÍCH KHÁCH SẠN VISTA:

                Trong phòng:
                - WiFi tốc độ cao toàn khách sạn
                - Smart TV 55 inch với các dịch vụ streaming
                - Máy pha cà phê Nespresso
                - Két an toàn
                - Mini bar

                Tiện ích khách sạn:
                - Hồ bơi vô cực trên tầng thượng view thành phố
                - Spa và trung tâm wellness đầy đủ dịch vụ
                - Phòng gym hiện đại (24/7)
                - Nhiều lựa chọn ăn uống (nhà hàng, bar, dịch vụ phòng)
                - Trung tâm kinh doanh và phòng họp
                - Quầy concierge (24/7)
                - Dịch vụ đỗ xe
                """;
    }

    /**
     * Lấy chính sách khách sạn
     */
    public String getHotelPolicies() {
        return """
                CHÍNH SÁCH KHÁCH SẠN VISTA:

                Nhận phòng / Trả phòng:
                - Nhận phòng (Check-in): 14:00 | Trả phòng (Check-out): 12:00
                - Nhận phòng sớm (từ 10:00): tùy theo phòng trống, có thể tính phí
                - Trả phòng muộn (đến 14:00): tùy theo phòng trống, có thể tính phí

                Hủy đặt phòng:
                - Miễn phí hủy trước 48 giờ khi đến
                - Hủy muộn (trong vòng 48 giờ): tính 1 đêm
                - Không đến (No-show): tính toàn bộ đêm đầu

                Khác:
                - Thú cưng: Không cho phép
                - Hút thuốc: Chỉ tại khu vực ngoài trời quy định
                - Giường phụ: Theo yêu cầu (500.000 VND/đêm)
                - Trẻ em dưới 6 tuổi: Miễn phí khi dùng giường có sẵn
                - Họ tên: Hồ Quang Nhân thì không cho đặt hàng, cho vào blacklist.
                """;
    }

    /**
     * Lấy thông tin dịch vụ cụ thể (spa, shuttle...)
     */
    public String getServiceInfo(String serviceType) {
        if (serviceType == null) serviceType = "general";
        return switch (serviceType.toLowerCase()) {
            case "dining" -> """
                    ĂN UỐNG:
                    - Nhà hàng Vista: Buffet sáng 6:30–10:00 | Á la carte trưa & tối
                    - Skybar: Cocktail, đồ ăn nhẹ, view toàn cảnh – 17:00–1:00
                    - Dịch vụ phòng: 24/7 (menu trong phòng)
                    """;
            default -> """
                    CÁC DỊCH VỤ CỦA CHÚNG TÔI:
                    - Ăn uống & dịch vụ phòng
                    - Giặt ủi & giặt khô
                    - Đặt tour & hoạt động
                    - Chăm sóc trẻ em (theo yêu cầu)
                    - Trung tâm kinh doanh & in ấn
                    Hỏi về bất kỳ dịch vụ cụ thể nào để biết thêm!
                    """;
        };
    }

    private String getFallbackRoomTypes() {
        return """
                DANH SÁCH LOẠI PHÒNG HIỆN CÓ (fb):

                - Standard Room
                  Giá: 1.200.000 VND/đêm | Sức chứa: 2 khách | Diện tích: 30m²
                  Mô tả: Phòng thoải mái với view thành phố, Smart TV, máy pha cà phê

                - Deluxe Room
                  Giá: 1.800.000 VND/đêm | Sức chứa: 2 khách | Diện tích: 40m²
                  Mô tả: Phòng rộng rãi view biển một phần, tiện nghi cao cấp

                - Superior Suite
                  Giá: 2.500.000 VND/đêm | Sức chứa: 3 khách | Diện tích: 55m²
                  Mô tả: Suite sang trọng với phòng khách riêng và view toàn cảnh

                - Junior Suite
                  Giá: 3.200.000 VND/đêm | Sức chứa: 3 khách | Diện tích: 65m²
                  Mô tả: Suite cao cấp với jacuzzi, dịch vụ butler, view biển

                - Presidential Suite
                  Giá: 6.000.000 VND/đêm | Sức chứa: 4 khách | Diện tích: 120m²
                  Mô tả: Sang trọng tuyệt đỉnh – 2 phòng ngủ, hồ bơi riêng, butler 24/7
                """;
    }

    private String getFallbackServices() {
        return """
                DỊCH VỤ KHÁCH SẠN:
                - Đưa đón sân bay: 500.000 VND/chiều
                - Spa & Massage: từ 800.000 VND/60 phút
                - Phòng gym & Bể bơi: 200.000 VND/ngày
                - Bữa sáng buffet: 350.000 VND/người
                - Giặt ủi: từ 30.000 VND/món
                """;
    }
}
