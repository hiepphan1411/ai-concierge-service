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

    @Value("${backend.service.url:http://localhost:8080}")
    private String backendServiceUrl;

    public HotelTools(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Lấy danh sách loại phòng và giá.
     * Gọi GET /api/room-types từ backend, fallback về hardcode nếu lỗi để test
     */
    public String getRoomTypes() {
        try {
            String url = backendServiceUrl + "/api/room-types";
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null
                    && !response.getBody().isEmpty()) {
                StringBuilder sb = new StringBuilder("AVAILABLE ROOM TYPES:\n\n");
                for (Map<String, Object> room : response.getBody()) {
                    sb.append("- ").append(room.getOrDefault("typeName", "Room")).append("\n");
                    sb.append("  Price: ").append(room.getOrDefault("basePrice", "N/A")).append(" VND/night\n");
                    sb.append("  Capacity: ").append(room.getOrDefault("maxOccupancy", "N/A")).append(" guests\n");
                    sb.append("  Size: ").append(room.getOrDefault("area", "N/A")).append("m²\n");
                    sb.append("  Description: ").append(room.getOrDefault("description", "")).append("\n\n");
                }
                log.debug("Fetched {} room types from backend", response.getBody().size());
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("Could not fetch room types from backend ({}), using fallback data", e.getMessage());
        }

        return """
                AVAILABLE ROOM TYPES:

                - Standard Room
                  Price: 1,200,000 VND/night | Capacity: 2 guests | Size: 30m²
                  Description: Cozy room with city view, Smart TV, Nespresso machine

                - Deluxe Room
                  Price: 1,800,000 VND/night | Capacity: 2 guests | Size: 40m²
                  Description: Spacious room with partial ocean view, premium amenities

                - Superior Suite
                  Price: 2,500,000 VND/night | Capacity: 3 guests | Size: 55m²
                  Description: Elegant suite with separate living area and panoramic view

                - Junior Suite
                  Price: 3,200,000 VND/night | Capacity: 3 guests | Size: 65m²
                  Description: Luxurious suite with jacuzzi, butler service, ocean view

                - Presidential Suite
                  Price: 6,000,000 VND/night | Capacity: 4 guests | Size: 120m²
                  Description: Ultimate luxury – 2 bedrooms, private pool, 24/7 butler
                """;
    }

    /**
     * Kiểm tra tình trạng phòng trống.
     * Gọi GET /api/rooms/available?checkIn=...&checkOut=...&guests=... từ backend.
     *
     * @param checkIn  Ngày nhận phòng (yyyy-MM-dd)
     * @param checkOut Ngày trả phòng (yyyy-MM-dd)
     * @param guests   Số khách
     */
    public String checkRoomAvailability(String checkIn, String checkOut, int guests) {
        try {
            String url = String.format(
                    "%s/api/rooms/available?checkIn=%s&checkOut=%s&guests=%d",
                    backendServiceUrl, checkIn, checkOut, guests
            );
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<Map<String, Object>> available = response.getBody();
                if (available.isEmpty()) {
                    return String.format(
                            "No rooms available from %s to %s for %d guests.", checkIn, checkOut, guests
                    );
                }
                StringBuilder sb = new StringBuilder(
                        String.format("Available rooms from %s to %s for %d guests:\n\n", checkIn, checkOut, guests)
                );
                for (Map<String, Object> room : available) {
                    sb.append("- ").append(room.getOrDefault("typeName", "Room")).append("\n");
                    sb.append("  Price: ").append(room.getOrDefault("pricePerNight", "N/A")).append(" VND/night\n");
                    sb.append("  Room Number: ").append(room.getOrDefault("roomNumber", "N/A")).append("\n\n");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("Could not check availability from backend ({}), using fallback", e.getMessage());
        }

        return String.format(
                """
                Room availability for %s to %s (%d guests):
                Based on typical availability, we recommend contacting our front desk
                or booking directly through our website for real-time availability.
                Check-in: 2:00 PM | Check-out: 12:00 PM
                """,
                checkIn != null ? checkIn : "requested date",
                checkOut != null ? checkOut : "requested date",
                guests > 0 ? guests : 2
        );
    }

    /**
     * Lấy danh sách tiện ích khách sạn.
     */
    public String getAmenities() {
        // Tiện ích thường không thay đổi -> hardcode là đủ
        // TODO: GỌI  khi backend hỗ trợ
        return """
                HOTEL AMENITIES:

                In-Room:
                - High-speed WiFi throughout the hotel
                - 55-inch Smart TV with streaming services
                - Nespresso coffee machine
                - Premium toiletries and bathrobes
                - Safe deposit box
                - Mini bar

                Hotel Facilities:
                - Rooftop infinity pool with panoramic city view
                - Full-service spa and wellness center
                - State-of-the-art fitness center (24/7)
                - Multiple dining options (restaurant, bar, room service)
                - Business center and meeting rooms
                - Concierge desk (24/7)
                - Valet parking
                """;
    }

    /**
     * Lấy chính sách khách sạn (check-in, hủy phòng...)
     */
    public String getHotelPolicies() {
        return """
                VISTA HOTEL POLICIES:

                Check-in / Check-out:
                - Check-in: 2:00 PM | Check-out: 12:00 PM
                - Early check-in (from 10:00 AM): subject to availability, fee may apply
                - Late check-out (until 2:00 PM): subject to availability, fee may apply

                Cancellation:
                - Free cancellation up to 48 hours before arrival
                - Late cancellation (within 48 hours): one night's charge
                - No-show: full first night charged

                Other:
                - Pets: Not allowed
                - Smoking: Designated outdoor areas only
                - Extra bed: Available on request (500,000 VND/night)
                - Children under 6: Stay free with existing bedding
                """;
    }

    /**
     * Lấy thông tin dịch vụ cụ thể.
     *
     * @param serviceType Loại dịch vụ: airport_shuttle | spa | dining | laundry | tour
     */
    public String getServiceInfo(String serviceType) {
        if (serviceType == null) serviceType = "general";
        return switch (serviceType.toLowerCase()) {
            case "airport_shuttle" -> """
                    AIRPORT SHUTTLE SERVICE:
                    - One-way transfer: 500,000 VND
                    - Round-trip transfer: 900,000 VND
                    - Vehicle: 7-seat luxury van
                    - Available 24/7, advance booking required (min. 3 hours)
                    - Contact: concierge@vistahοtel.vn or front desk ext. 0
                    """;
            case "spa" -> """
                    SPA & WELLNESS:
                    - Swedish Massage (60 min): 800,000 VND
                    - Deep Tissue Massage (90 min): 1,200,000 VND
                    - Couple's Package (120 min): 2,500,000 VND
                    - Facial Treatment (75 min): 1,000,000 VND
                    - Operating hours: 9:00 AM – 10:00 PM daily
                    - Advance booking recommended: ext. 5 or spa@vistahotel.vn
                    """;
            case "dining" -> """
                    DINING OPTIONS:
                    - Vista Restaurant: Buffet breakfast 6:30–10:00 AM | Á la carte lunch & dinner
                    - Skybar: Cocktails, light bites, panoramic view – 5:00 PM–1:00 AM
                    - Room Service: Available 24/7 (menu in your room)
                    - Breakfast included in selected room packages
                    """;
            case "laundry" -> """
                    LAUNDRY SERVICE:
                    - Express (4 hours): +50% surcharge
                    - Standard (next day): Shirt 30,000 VND | Trousers 35,000 VND | Suit 80,000 VND
                    - Dry cleaning available
                    - Drop bag at front desk or call housekeeping (ext. 3)
                    """;
            case "tour" -> """
                    TOUR & ACTIVITIES:
                    - City tour (half day): 450,000 VND/person
                    - City tour (full day): 750,000 VND/person
                    - Cooking class: 600,000 VND/person
                    - Ha Long Bay day trip: From 1,800,000 VND/person
                    - Book at concierge desk or ext. 6
                    """;
            default -> """
                    OUR SERVICES:
                    - Airport shuttle transfers
                    - Spa & wellness treatments
                    - Fine dining & room service
                    - Laundry & dry cleaning
                    - Tour & activity bookings
                    - Babysitting (on request)
                    - Business center & printing
                    Please ask for details on any specific service!
                    """;
        };
    }
}
