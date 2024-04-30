package com.university.mcmaster.services.impl;

import com.google.firebase.auth.UserRecord;
import com.university.mcmaster.controllers.LogInResponseDto;
import com.university.mcmaster.enums.UserRole;
import com.university.mcmaster.enums.VerificationStatus;
import com.university.mcmaster.exceptions.*;
import com.university.mcmaster.models.dtos.request.ApiResponse;
import com.university.mcmaster.models.dtos.request.UpdateUserRequestDto;
import com.university.mcmaster.models.dtos.response.RentalUnitOwnerLogInResponse;
import com.university.mcmaster.models.dtos.response.StudentLogInResponse;
import com.university.mcmaster.models.entities.Address;
import com.university.mcmaster.models.entities.CustomUserDetails;
import com.university.mcmaster.models.entities.RentalUnit;
import com.university.mcmaster.models.entities.User;
import com.university.mcmaster.repositories.UserRepo;
import com.university.mcmaster.services.UserService;
import com.university.mcmaster.utils.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.units.qual.A;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);
    private final UserRepo userRepo;
    private final ResponseMapper responseMapper;

    @EventListener
    public void createAdminUser(ApplicationStartedEvent event){
        String email = EnvironmentVariables.ADMIN_EMAIL;
        if(null != email && false == email.isEmpty()){
            log.trace("checking for admin account for email : " + email);
            User user = userRepo.findUserByEmail(email);
            if(null == user){
                log.trace("user admin not account found, creating new admin account for : " + email);
                UserRecord record = FirebaseAuthenticationService.createAdminAccount(email);
                if(null != record){
                    user = User.builder()
                            .createdOn(Instant.now().toEpochMilli())
                            .id(record.getUid())
                            .email(email.trim().toLowerCase())
                            .name("mcmaster admin")
                            .verificationStatus(VerificationStatus.verified)
                            .role(Arrays.asList(UserRole.user,UserRole.admin))
                            .build();
                    userRepo.save(user);
                    User finalUser1 = user;
                    FirebaseAuthenticationService.updateClaims(user.getId(),new HashMap<String, Object>(){{
                        put("roles", finalUser1.getRole().stream().map(UserRole::toString).collect(Collectors.joining(",")));
                        put("verified", true);
                    }});
                }else{
                    log.trace("failed to create admin account");
                }
            }else if(false == user.getRole().contains(UserRole.admin)) {
                log.trace("found an existing admin account with missing admin role, updating user");
                User finalUser = user;
                user.getRole().add(UserRole.admin);
                userRepo.update(user.getId(),new HashMap<String, Object>(){{
                    put("role",finalUser.getRole());
                }});
                FirebaseAuthenticationService.updateClaims(user.getId(),new HashMap<String, Object>(){{
                    put("roles", finalUser.getRole().stream().map(UserRole::toString).collect(Collectors.joining(",")));
                    put("verified", true);
                }});
            }
        }else{
            log.trace("no admin email set");
        }
    }

    @Override
    public User findUserById(String id) {
        return userRepo.findById(id);
    }

    @Override
    public boolean saveUser(User user) {
        return userRepo.save(user);
    }

    @Override
    public ResponseEntity<?> updateUser(UpdateUserRequestDto requestDto, String requestId, HttpServletRequest request) {
        CustomUserDetails userDetails = Utility.customUserDetails(request);
        if(null == userDetails) throw new UnAuthenticatedUserException();
        if(userDetails.getRoles().contains(UserRole.student)) return updateStudentUnAuth(userDetails.getId(),requestDto,false,requestId);
        if(userDetails.getRoles().contains(UserRole.rental_unit_owner)) return updateRentalUnitOwnerUserUnAuth(userDetails.getId(),requestDto,false,requestId);
        throw new ActionNotAllowedException("update_user","invalid user role",400);
    }

    @Override
    public void updateUser(String userId, Map<String, Object> updateMap) {
        userRepo.update(userId,updateMap);
    }

    @Override
    public List<User> getPaginatedUsersByVerificationStatusForAdmin(VerificationStatus verificationStatus, int limit, String lastSeen) {
        List<User> users = userRepo.getPaginatedUsersByVerificationStatusForAdmin(verificationStatus,limit,lastSeen);
        for (User user : users) {
            user.setCustomFields(new HashMap<String, Object>(){{
                put("documents",responseMapper.getStudentDocs(user));
            }});
        }
        return users;
    }

    @Override
    public ResponseEntity<?> getUserDetails(String requestId, HttpServletRequest request) {
        CustomUserDetails userDetails = Utility.customUserDetails(request);
        if(null == userDetails || null == userDetails.getRoles()) throw new UnAuthenticatedUserException();
        User user = userRepo.findById(userDetails.getId());
        LogInResponseDto responseDto = new LogInResponseDto();
        if(null == user) {
            return ResponseEntity.ok(ApiResponse.builder().build());
        }
        responseDto.setRegistered(true);
        if(user.getRole().contains(UserRole.student)) {
            responseDto.setStudent(StudentLogInResponse.builder()
                    .email(user.getEmail())
                    .phoneNumber(user.getPhoneNumber())
                    .verificationStatus(user.getVerificationStatus())
                    .name(user.getName())
                    .userRole(UserRole.student)
                    .admin(user.getRole().contains(UserRole.admin))
                    .documents(responseMapper.getStudentDocs(user))
                            .verifiedOn(user.getVerifiedOn())
                            .dob(user.getDob())
                            .nationality(user.getNationality())
                            .emergencyContact(user.getEmergencyContact())
                            .additionalEmail(user.getAdditionalEmail())
                            .addresses(user.getAddresses())
                            .reason(user.getReason())
                    .build());
        } else if(user.getRole().contains(UserRole.rental_unit_owner)) {
            responseDto.setRentalUnitOwner(RentalUnitOwnerLogInResponse.builder()
                    .email(user.getEmail())
                    .phoneNumber(user.getPhoneNumber())
                    .name(user.getName())
                    .admin(user.getRole().contains(UserRole.admin))
                    .userRole(UserRole.rental_unit_owner)
                            .preferredModOfContact(user.getPreferredModOfContact())
                            .occupation(user.getOccupation())
                            .reason(user.getReason())
                    .build());
        }
        return ResponseEntity.ok(responseDto);
    }

    @Override
    public ResponseEntity<?> searchUserForApplication(String email, String requestId, HttpServletRequest request) {
        CustomUserDetails userDetails = Utility.customUserDetails(request);
        if(null == userDetails || null == userDetails.getRoles()) throw new UnAuthenticatedUserException();
        if(false == userDetails.getRoles().contains(UserRole.student)) throw new UnAuthenticatedUserException();
        if(null == email || email.trim().isEmpty()) throw new MissingRequiredParamException();
        if(false == Utility.isValidEmail(email)) throw new InvalidParamValueException();
        User user = userRepo.findUserByEmail(email);
        if(null == user) throw new EntityNotFoundException();
        return ResponseEntity.ok(ApiResponse.builder()
                        .data(responseMapper.getStudentForStudent(user))
                .build());
    }

    private ResponseEntity<?> updateRentalUnitOwnerUserUnAuth(String userId, UpdateUserRequestDto requestDto, boolean isAdmin, String requestId) {
        User user = userRepo.findById(userId);
        if(null == user) throw new EntityNotFoundException();
        Map<String,Object> updateMap = new HashMap<>();
        String phoneNumber = Optional.ofNullable(requestDto.getPhoneNumber()).map(s->s.trim()).orElse("");
        String name = Optional.ofNullable(requestDto.getName()).map(s->s.trim()).orElse("");
        String occupation = Optional.ofNullable(requestDto.getOccupation()).map(s->s.trim()).orElse("");
        String preferredModOfContact = Optional.ofNullable(requestDto.getPreferredModOfContact()).map(s->s.trim()).orElse("phone");

        if(false == phoneNumber.isEmpty() && false == phoneNumber.equals(user.getPhoneNumber())){
            updateMap.put("phoneNumber",phoneNumber);
        }
        if(false == name.isEmpty() && false == name.equals(user.getName())){
            updateMap.put("name",name);
        }
        if(false == occupation.isEmpty() && false == occupation.equals(user.getOccupation())){
            updateMap.put("occupation",occupation);
        }
        if(false == preferredModOfContact.isEmpty() && false == preferredModOfContact.equals(user.getPreferredModOfContact())){
            updateMap.put("preferredModOfContact",preferredModOfContact);
        }
        if(false == updateMap.isEmpty()) userRepo.update(user.getId(),updateMap);
        return ResponseEntity.status(200).body(ApiResponse.builder()
                .status(200)
                .data(updateMap)
                .msg("updated user")
                .build());
    }

    private ResponseEntity<?> updateStudentUnAuth(String userId, UpdateUserRequestDto requestDto, boolean isAdmin, String requestId) {
        User user = userRepo.findById(userId);
        if(null == user) throw new EntityNotFoundException();
        Map<String,Object> updateMap = new HashMap<>();
//        phoneNumber , name , dob , nationality , emergencyContact , additionalEmail , address
        String phoneNumber = Optional.ofNullable(requestDto.getPhoneNumber()).map(s->s.trim()).orElse("");
        String name = Optional.ofNullable(requestDto.getName()).map(s->s.trim()).orElse("");
        String dob = Optional.ofNullable(requestDto.getDob()).map(s->s.trim()).orElse("");
        String nationality = Optional.ofNullable(requestDto.getNationality()).map(s->s.trim()).orElse("");
        String emergencyContact = Optional.ofNullable(requestDto.getEmergencyContact()).map(s->s.trim().toLowerCase()).orElse("");
        String additionalEmail = Optional.ofNullable(requestDto.getAdditionalEmail()).map(s->s.trim().toLowerCase()).orElse("");
        List<Address> validatedAddresses = velidateAndSanitizeAddresses(requestDto.getAddresses());

        if(false == phoneNumber.isEmpty() && false == phoneNumber.equals(user.getPhoneNumber())){
            log.trace("[update_student] updating 'phoneNumber'");
            updateMap.put("phoneNumber",phoneNumber);
        }
        if(false == name.isEmpty() && false == name.equals(user.getName())){
            log.trace("[update_student] updating 'name'");
            updateMap.put("name",name);
        }
        if(false == dob.isEmpty() && false == dob.equals(user.getDob())){
            log.trace("[update_student] updating 'dob'");
            updateMap.put("dob",dob);
        }
        if(false == emergencyContact.isEmpty() && false == emergencyContact.equals(user.getEmergencyContact())){
            log.trace("[update_student] updating 'emergencyContact'");
            updateMap.put("emergencyContact",emergencyContact);
        }
        if(false == additionalEmail.isEmpty() && false == additionalEmail.equals(user.getAdditionalEmail())){
            log.trace("[update_student] updating 'additionalEmail'");
            updateMap.put("additionalEmail",additionalEmail);
        }
        if(false == validatedAddresses.isEmpty() && false == Utility.areListsEqual(validatedAddresses,user.getAddresses())){
            log.trace("[update_student] updating 'validatedAddresses'");
            updateMap.put("addresses",validatedAddresses);
        }
        if(VerificationStatus.verified != user.getVerificationStatus() || isAdmin){
            if(false == nationality.isEmpty() && false == nationality.equals(user.getNationality())){
                log.trace("[update_student] updating 'nationality'");
                updateMap.put("nationality",nationality);
            }
        }
        if(false == updateMap.isEmpty()) userRepo.update(user.getId(),updateMap);
        return ResponseEntity.status(200).body(ApiResponse.builder()
                .status(200)
                        .data(updateMap)
                        .msg("updated user")
                .build());
    }

    private List<Address> velidateAndSanitizeAddresses(List<Address> addresses) {
        if(addresses == null || addresses.isEmpty()) return Collections.emptyList();
        for (Address address : addresses) {
//            country , state , zip , city
            String country = Optional.ofNullable(address.getCountry()).map(s->s.trim().toLowerCase()).orElse("");
            String state = Optional.ofNullable(address.getState()).map(s->s.trim().toLowerCase()).orElse("");
            String zip = Optional.ofNullable(address.getZip()).map(s->s.trim().toLowerCase()).orElse("");
            String city = Optional.ofNullable(address.getCity()).map(s->s.trim().toLowerCase()).orElse("");
            List<String> missingProps = new ArrayList<>();
            if(country.isEmpty()) missingProps.add("country");
            if(state.isEmpty()) missingProps.add("state");
            if(zip.isEmpty()) missingProps.add("zip");
            if(city.isEmpty()) missingProps.add("city");
            if(false == missingProps.isEmpty()) throw new MissingRequiredParamException(missingProps.toString());
            address.setCity(city);
            address.setCountry(country);
            address.setZip(zip);
            address.setState(state);
        }
        return addresses;
    }
}
