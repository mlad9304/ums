package gov.samhsa.c2s.ums.service;

import gov.samhsa.c2s.ums.config.UmsProperties;
import gov.samhsa.c2s.ums.domain.Address;
import gov.samhsa.c2s.ums.domain.AddressRepository;
import gov.samhsa.c2s.ums.domain.Demographics;
import gov.samhsa.c2s.ums.domain.DemographicsRepository;
import gov.samhsa.c2s.ums.domain.Identifier;
import gov.samhsa.c2s.ums.domain.IdentifierRepository;
import gov.samhsa.c2s.ums.domain.IdentifierSystem;
import gov.samhsa.c2s.ums.domain.IdentifierSystemRepository;
import gov.samhsa.c2s.ums.domain.LocaleRepository;
import gov.samhsa.c2s.ums.domain.Patient;
import gov.samhsa.c2s.ums.domain.PatientRepository;
import gov.samhsa.c2s.ums.domain.RoleRepository;
import gov.samhsa.c2s.ums.domain.Telecom;
import gov.samhsa.c2s.ums.domain.TelecomRepository;
import gov.samhsa.c2s.ums.domain.User;
import gov.samhsa.c2s.ums.domain.UserPatientRelationship;
import gov.samhsa.c2s.ums.domain.UserPatientRelationshipRepository;
import gov.samhsa.c2s.ums.domain.UserRepository;
import gov.samhsa.c2s.ums.domain.reference.AdministrativeGenderCode;
import gov.samhsa.c2s.ums.domain.reference.AdministrativeGenderCodeRepository;
import gov.samhsa.c2s.ums.domain.reference.CountryCodeRepository;
import gov.samhsa.c2s.ums.domain.reference.StateCodeRepository;
import gov.samhsa.c2s.ums.domain.valueobject.UserPatientRelationshipId;
import gov.samhsa.c2s.ums.infrastructure.ScimService;
import gov.samhsa.c2s.ums.service.dto.AccessDecisionDto;
import gov.samhsa.c2s.ums.service.dto.AddressDto;
import gov.samhsa.c2s.ums.service.dto.IdentifierDto;
import gov.samhsa.c2s.ums.service.dto.RelationDto;
import gov.samhsa.c2s.ums.service.dto.TelecomDto;
import gov.samhsa.c2s.ums.service.dto.UserDto;
import gov.samhsa.c2s.ums.service.exception.IdentifierSystemNotFoundException;
import gov.samhsa.c2s.ums.service.exception.MissingEmailException;
import gov.samhsa.c2s.ums.service.exception.PatientNotFoundException;
import gov.samhsa.c2s.ums.service.exception.SsnSystemNotFoundException;
import gov.samhsa.c2s.ums.service.exception.UserActivationNotFoundException;
import gov.samhsa.c2s.ums.service.exception.UserNotFoundException;
import gov.samhsa.c2s.ums.service.fhir.FhirPatientService;
import gov.samhsa.c2s.ums.service.mapping.PatientToMrnConverter;
import gov.samhsa.c2s.ums.service.mapping.UserToMrnConverter;
import lombok.extern.slf4j.Slf4j;
import org.modelmapper.ModelMapper;
import org.modelmapper.TypeToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.transaction.Transactional;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringTokenizer;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;


@Service
@Slf4j
public class UserServiceImpl implements UserService {

    private static final Integer PAGE_NUMBER = 0;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdministrativeGenderCodeRepository administrativeGenderCodeRepository;
    @Autowired
    private UmsProperties umsProperties;
    @Autowired
    private ModelMapper modelMapper;
    @Autowired
    private MrnService mrnService;
    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private LocaleRepository localeRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private StateCodeRepository stateCodeRepository;
    @Autowired
    private CountryCodeRepository countryCodeRepository;

    @Autowired
    private TelecomRepository telecomRepository;
    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private UserPatientRelationshipRepository userPatientRelationshipRepository;
    @Autowired
    private ScimService scimService;
    @Autowired
    private DemographicsRepository demographicsRepository;

    @Autowired
    private FhirPatientService fhirPatientService;

    @Autowired
    private IdentifierSystemRepository identifierSystemRepository;
    @Autowired
    private IdentifierRepository identifierRepository;

    @Autowired
    private UserToMrnConverter userToMrnConverter;

    @Autowired
    private PatientToMrnConverter patientToMrnConverter;

    @Override
    @Transactional
    public void registerUser(UserDto userDto) {

        // Step 1: Create User Record and User Role Mapping in UMS

        /* Get User Entity from UserDto */
        final User user = modelMapper.map(userDto, User.class);

        // Identifiers
        final List<Identifier> identifiers = userDto.getIdentifiers().stream()
                .map(idDto -> identifierRepository
                        .findByValueAndIdentifierSystemSystem(idDto.getValue(), idDto.getSystem())
                        .orElseGet(() -> createIdentifier(idDto)))
                .peek(identifierRepository::save)
                .collect(toList());
        user.getDemographics().setIdentifiers(identifiers);

        // SSN
        userDto.getSocialSecurityNumber().ifPresent(socialSecurityNumber -> {
            final IdentifierSystem identifierSystem = identifierSystemRepository.findBySystem(umsProperties.getSsn().getCodeSystem()).orElseThrow(SsnSystemNotFoundException::new);
            final Identifier ssnIdentifier = Identifier.of(socialSecurityNumber, identifierSystem);
            identifierRepository.save(ssnIdentifier);
            user.getDemographics().getIdentifiers().add(ssnIdentifier);
        });

        // Add user contact details to Telecom Table
        user.getDemographics().setTelecoms(modelMapper.map(userDto.getTelecoms(), new TypeToken<List<Telecom>>() {
        }.getType()));
        for (Telecom telecom : user.getDemographics().getTelecoms())
            telecom.setDemographics(user.getDemographics());

        user.getDemographics().setAddresses(modelMapper.map(userDto.getAddresses(), new TypeToken<List<Address>>() {
        }.getType()));
        for (Address address : user.getDemographics().getAddresses())
            address.setDemographics(user.getDemographics());

        userRepository.save(user);

        /*
        Step 2: Create User Patient Record in UMS  if User is a Patient
        Add User Patient Record if the role is patient
        TODO remove the hardcoding with FHIR enum value
        */
        if (userDto.getRoles().stream().anyMatch(roleDto -> roleDto.getCode().equalsIgnoreCase("patient"))) {
            // Assert that the patient has at least one email OR a registrationPurposeEmail
            final boolean patientHasEmail = user.getDemographics().getTelecoms().stream().map(Telecom::getSystem).anyMatch(Telecom.System.EMAIL::equals);
            if (!patientHasEmail && !StringUtils.hasText(userDto.getRegistrationPurposeEmail())) {
                throw new MissingEmailException("At least one of personal email OR a registration purpose email is required");
            }

            Patient patient = createPatient(user, userDto.getRegistrationPurposeEmail());
            // Step 2.1: Create User Patient Relationship Mapping in UMS
            // Add User patient relationship if User is a Patient
            createUserPatientRelationship(user.getId(), patient.getId(), "patient");
            // Publish FHIR Patient to FHir Service
            if (umsProperties.getFhir().getPublish().isEnabled()) {
                userDto.setMrn(patientToMrnConverter.convert(patient));
                fhirPatientService.publishFhirPatient(userDto);
            }
        }

    }

    @Override
    @Transactional
    public void disableUser(Long userId) {
        //Check if user account has been activated
        assertUserAccountHasBeenActivated(userId);
        //Set isDisabled to true in the User table
        User user = userRepository.findOneByIdAndDisabled(userId, false)
                .orElseThrow(() -> new UserNotFoundException("User Not Found!"));
        user.setDisabled(true);
        //
        /**
         * Use OAuth API to set users.active to false.
         * Doing so will not let a user to login.
         * Also known as "Soft Delete".
         */
        scimService.inactivateUser(user.getUserAuthId());
        User save = userRepository.save(user);
    }

    @Override
    public void enableUser(Long userId) {
        //Check if user account has been activated
        assertUserAccountHasBeenActivated(userId);
        //Set isDisabled to false in the User table
        User user = userRepository.findOneByIdAndDisabled(userId, true)
                .orElseThrow(() -> new UserNotFoundException("User Not Found!"));
        user.setDisabled(false);

        /**
         * Use OAuth API to set users.active to true.

         */
        scimService.activateUser(user.getUserAuthId());
        User save = userRepository.save(user);
    }

    @Override
    public void updateUser(Long userId, UserDto userDto) {

        /* Get User Entity from UserDto */
        final User user = userRepository.findOneById(userId).orElseThrow(UserNotFoundException::new);

        user.setLocale(localeRepository.findByCode(userDto.getLocale()));
        user.setRoles(userDto.getRoles().stream().flatMap(roleDto -> roleRepository.findAllByCode(roleDto.getCode()).stream()).collect(Collectors.toSet()));
        user.getDemographics().setMiddleName(userDto.getMiddleName());
        user.getDemographics().setFirstName(userDto.getFirstName());
        user.getDemographics().setLastName(userDto.getLastName());
        user.getDemographics().setBirthDay(userDto.getBirthDate());

        // Update registration purpose email
        user.getDemographics().getPatient().setRegistrationPurposeEmail(userDto.getRegistrationPurposeEmail());

        // Identifiers
        final List<Identifier> identifiersToRemove = user.getDemographics().getIdentifiers().stream()
                .filter(id -> id.getIdentifierSystem().isSystemGenerated() == false)
                .filter(id -> userDto.getIdentifiers().stream()
                        .noneMatch(idDto -> deepEquals(id, idDto)))
                .collect(toList());
        user.getDemographics().getIdentifiers().removeAll(identifiersToRemove);
        final List<Identifier> identifiersToAdd = userDto.getIdentifiers().stream()
                .filter(idDto -> user.getDemographics().getIdentifiers().stream()
                        .noneMatch(id -> deepEquals(id, idDto)))
                .map(idDto -> identifierRepository.findByValueAndIdentifierSystemSystem(idDto.getValue(), idDto.getSystem())
                        .orElseGet(() -> createIdentifier(idDto)))
                .collect(toList());
        identifierRepository.save(identifiersToAdd);
        user.getDemographics().getIdentifiers().addAll(identifiersToAdd);

        // Update SSN
        // Find new SSN value
        final Optional<String> newSsnValue = userDto.getSocialSecurityNumber().filter(StringUtils::hasText).map(String::trim);
        // Find old SSN identifier
        final Optional<Identifier> oldSsnIdentifier = user.getDemographics().getIdentifiers().stream()
                .filter(id -> umsProperties.getSsn().getCodeSystem().equals(id.getIdentifierSystem().getSystem()))
                .findAny();
        // Filter old SSN identifier if different
        final Optional<Identifier> oldSsnIdentifierIfDifferent = oldSsnIdentifier.filter(oldId -> !newSsnValue.filter(ssnValue -> oldId.getValue().equals(ssnValue)).isPresent());
        // Delete old SSN if different
        oldSsnIdentifierIfDifferent.ifPresent(user.getDemographics().getIdentifiers()::remove);
        oldSsnIdentifierIfDifferent.ifPresent(identifierRepository::delete);

        // Update SSN with new value if different
        newSsnValue.filter(ssn -> !oldSsnIdentifier.map(Identifier::getValue).filter(ssn::equals).isPresent())
                .ifPresent(ssn -> {
                    final IdentifierSystem ssnSystem = identifierSystemRepository.findBySystem(umsProperties.getSsn().getCodeSystem()).orElseThrow(SsnSystemNotFoundException::new);
                    final Identifier ssnIdentifier = identifierRepository.findByValueAndIdentifierSystem(ssn, ssnSystem).orElseGet(() -> Identifier.of(ssn, ssnSystem));
                    identifierRepository.save(ssnIdentifier);
                    user.getDemographics().getIdentifiers().add(ssnIdentifier);
                });

        user.getDemographics().setAdministrativeGenderCode(administrativeGenderCodeRepository.findByCode(userDto.getGenderCode()));

        //update address
        List<Address> addresses = user.getDemographics().getAddresses();
        if (userDto.getAddresses() != null) {
            userDto.getAddresses().stream().forEach(addressDto -> {
                Optional<Address> tempAddress = addresses.stream().filter(address -> address.getUse().toString().equals(addressDto.getUse())).findFirst();
                if (tempAddress.isPresent()) {
                    mapAddressDtoToAddress(tempAddress.get(), addressDto);
                } else {
                    Address address = mapAddressDtoToAddress(new Address(), addressDto);
                    address.setDemographics(user.getDemographics());
                    addresses.add(address);
                }
            });
        }

        //update telephone
        List<Telecom> telecoms = user.getDemographics().getTelecoms();
        if (userDto.getTelecoms() != null) {
            userDto.getTelecoms().stream().forEach(telecomDto -> {
                Optional<Telecom> tempTeleCom = telecoms.stream().filter(telecom -> telecom.getSystem().toString().equals(telecomDto.getSystem()) && telecom.getUse().toString().equals(telecomDto.getUse())).findFirst();
                if (tempTeleCom.isPresent()) {
                    tempTeleCom.get().setValue(telecomDto.getValue());
                } else {
                    Telecom telecom = mapTelecomDtoToTelcom(new Telecom(), telecomDto);
                    telecom.setDemographics(user.getDemographics());
                    telecoms.add(telecom);
                }
            });
        }

        if (umsProperties.getFhir().getPublish().isEnabled() && user.getDemographics().getPatient() != null) {
            userDto.setMrn(userToMrnConverter.convert(user));
            fhirPatientService.updateFhirPatient(userDto);
        }

        userRepository.save(user);
    }

    @Override
    public void updateUserLocale(Long userId, String localeCode) {

        /* Get User Entity from UserDto */
        User user = userRepository.findOne(userId);
        user.setLocale(localeRepository.findByCode(localeCode));
        user = userRepository.save(user);
    }

    @Override
    public void updateUserLocaleByUserAuthId(String userAuthId, String localeCode) {

        /* Get User Entity from UserDto */
        User user = userRepository.findOneByUserAuthIdAndDisabled(userAuthId, false).orElseThrow(() -> new UserNotFoundException("User Not Found!"));
        user.setLocale(localeRepository.findByCode(localeCode));
        user = userRepository.save(user);
    }

    @Override
    public AccessDecisionDto accessDecision(String userAuthId, String patientMrn) {
        final User user = userRepository.findOneByUserAuthIdAndDisabled(userAuthId, false).orElseThrow(() -> new UserNotFoundException("User Not Found!"));
        final Patient patient = demographicsRepository.findOneByIdentifiersValueAndIdentifiersIdentifierSystemSystem(patientMrn, umsProperties.getMrn().getCodeSystem())
                .map(Demographics::getPatient)
                .orElseThrow(() -> new PatientNotFoundException("Patient Not Found!"));
        List<UserPatientRelationship> userPatientRelationshipList = userPatientRelationshipRepository.findAllByIdUserIdAndIdPatientId(user.getId(), patient.getId());

        if (userPatientRelationshipList == null || userPatientRelationshipList.size() < 1) {
            return new AccessDecisionDto(false);
        } else
            return new AccessDecisionDto(true);
    }

    @Override
    public UserDto getUser(Long userId) {
        final User user = userRepository.findOne(userId);
        return modelMapper.map(user, UserDto.class);
    }

    @Override
    public UserDto getUserByUserAuthId(String userAuthId) {
        final User user = userRepository.findOneByUserAuthIdAndDisabled(userAuthId, false)
                .orElseThrow(() -> new UserNotFoundException("User Not Found!"));
        return modelMapper.map(user, UserDto.class);
    }

    @Override
    public Page<UserDto> getAllUsers(Optional<Integer> page, Optional<Integer> size) {
        final PageRequest pageRequest = new PageRequest(page.filter(p -> p >= 0).orElse(0),
                size.filter(s -> s > 0 && s <= umsProperties.getPagination().getMaxSize())
                        .orElse(umsProperties.getPagination().getDefaultSize()));
        final Page<User> usersPage = userRepository.findAll(pageRequest);
        final List<User> userList = usersPage.getContent();
        final List<UserDto> getUserDtoList = userListToUserDtoList(userList);
        return new PageImpl<>(getUserDtoList, pageRequest, usersPage.getTotalElements());
    }

    @Override
    public List<UserDto> searchUsersByDemographic(String firstName,
                                                  String lastName,
                                                  LocalDate birthDate,
                                                  String genderCode) {
        List<Demographics> demographicsesList;
        final AdministrativeGenderCode administrativeGenderCode = administrativeGenderCodeRepository.findByCode(genderCode);
        demographicsesList = demographicsRepository.findAllByFirstNameAndLastNameAndBirthDayAndAdministrativeGenderCode(firstName, lastName,
                birthDate, administrativeGenderCode);
        if (demographicsesList.size() < 1) {
            throw new UserNotFoundException("User Not Found!");
        } else {
            return demographicsesListToUserDtoList(demographicsesList);
        }
    }

    @Override
    public List<UserDto> searchUsersByIdentifier(String value, String system) {
        return userRepository.findAllByDemographicsIdentifiersValueAndDemographicsIdentifiersIdentifierSystemSystem(value, system)
                .stream()
                .map(user -> modelMapper.map(user, UserDto.class))
                .collect(toList());
    }

    public List<UserDto> searchUsersByFirstNameAndORLastName(StringTokenizer token) {
        Pageable pageRequest = new PageRequest(PAGE_NUMBER, umsProperties.getPagination().getDefaultSize());
        if (token.countTokens() == 1) {
            String firstName = token.nextToken(); // First Token could be first name or the last name
            return demographicsRepository.findAllByFirstNameLikesOrLastNameLikes("%" + firstName + "%", pageRequest)
                    .stream()
                    .map(demographics -> modelMapper.map(demographics.getUser(), UserDto.class))
                    .collect(toList());
        } else if (token.countTokens() >= 2) {
            String firstName = token.nextToken(); // First Token is the first name
            String lastName = token.nextToken();  // Last Token is the last name
            return demographicsRepository.findAllByFirstNameLikesAndLastNameLikes("%" + firstName + "%", "%" + lastName + "%", pageRequest)
                    .stream()
                    .map(demographics -> modelMapper.map(demographics.getUser(), UserDto.class))
                    .collect(toList());
        } else {
            return new ArrayList<>();
        }
    }

    private Identifier createIdentifier(IdentifierDto idDto) {
        return Identifier.of(idDto.getValue(), identifierSystemRepository
                .findBySystemAndSystemGeneratedIsFalse(idDto.getSystem())
                .orElseThrow(() -> new IdentifierSystemNotFoundException("Identifier System is not found or it can be only generated by the system")));
    }

    private Patient createPatient(User user, String registrationPurposeEmail) {
        //set the patient object
        Patient patient = new Patient();
        final List<IdentifierSystem> systems = identifierSystemRepository.findAllBySystemGenerated(true);
        final List<Identifier> identifiers = systems.stream()
                .map(system -> Identifier.builder().identifierSystem(system).value(mrnService.generateMrn()).build())
                .collect(toList());
        identifierRepository.save(identifiers);
        final Demographics demographics = user.getDemographics();
        demographics.getIdentifiers().addAll(identifiers);
        patient.setDemographics(demographics);
        patient.setRegistrationPurposeEmail(registrationPurposeEmail);
        return patientRepository.save(patient);
    }

    private void createUserPatientRelationship(long userId, long patientId, String role) {
        RelationDto relationDto = new RelationDto(userId, patientId, role);
        UserPatientRelationship userPatientRelationship = new UserPatientRelationship();
        userPatientRelationship.setId(modelMapper.map(relationDto, UserPatientRelationshipId.class));
        userPatientRelationshipRepository.save(userPatientRelationship);
    }

    private boolean deepEquals(Identifier id, IdentifierDto idDto) {
        return id.getValue().equals(idDto.getValue()) && id.getIdentifierSystem().getSystem().equals(idDto.getSystem());
    }

    private Address mapAddressDtoToAddress(Address address, AddressDto addressDto) {
        address.setCity(addressDto.getCity());
        address.setStateCode(stateCodeRepository.findByCode(addressDto.getStateCode()));
        address.setCountryCode(countryCodeRepository.findByCode(addressDto.getCountryCode()));
        address.setLine1(addressDto.getLine1());
        address.setLine2(addressDto.getLine2());
        address.setPostalCode(addressDto.getPostalCode());
        if (addressDto.getUse().equals(Address.Use.HOME.toString()))
            address.setUse(Address.Use.HOME);
        if (addressDto.getUse().equals(Address.Use.WORK.toString()))
            address.setUse(Address.Use.WORK);
        return address;
    }

    private Telecom mapTelecomDtoToTelcom(Telecom telecom, TelecomDto telecomDto) {
        telecom.setValue(telecomDto.getValue());

        if (telecomDto.getUse().equals(Telecom.Use.HOME.toString()))
            telecom.setUse(Telecom.Use.HOME);
        if (telecomDto.getUse().equals(Telecom.Use.WORK.toString()))
            telecom.setUse(Telecom.Use.WORK);

        if (telecomDto.getSystem().equals(Telecom.System.EMAIL.toString()))
            telecom.setSystem(Telecom.System.EMAIL);
        if (telecomDto.getSystem().equals(Telecom.System.PHONE.toString()))
            telecom.setSystem(Telecom.System.PHONE);

        return telecom;
    }

    private List<UserDto> demographicsesListToUserDtoList(List<Demographics> demographicsesList) {
        List<UserDto> getUserDtoList = new ArrayList<>();

        if (demographicsesList != null && demographicsesList.size() > 0) {
            for (Demographics temp : demographicsesList) {
                getUserDtoList.add(modelMapper.map(temp.getUser(), UserDto.class));
            }
        }
        return getUserDtoList;
    }

    private List<UserDto> userListToUserDtoList(List<User> userList) {
        List<UserDto> getUserDtoList = new ArrayList<>();

        if (userList != null && userList.size() > 0) {
            for (User temp : userList) {
                getUserDtoList.add(modelMapper.map(temp, UserDto.class));
            }
        }
        return getUserDtoList;
    }

    private void assertUserAccountHasBeenActivated(Long userId) {
        userRepository.findOneById(userId)
                .map(User::getUserAuthId)
                .filter(StringUtils::hasText)
                .orElseThrow(UserActivationNotFoundException::new);
    }
}
