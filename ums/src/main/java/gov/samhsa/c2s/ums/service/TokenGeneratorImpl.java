package gov.samhsa.c2s.ums.service;

import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.security.SecureRandom;

@Service
public class TokenGeneratorImpl implements TokenGenerator {
    @Override
    public String generateToken() {
        SecureRandom random = new SecureRandom();
        return new BigInteger(130, random).toString(32);
    }

    @Override
    public String generateToken(int maxLength) {
        final String token = generateToken();
        return token.substring(0, Integer.min(token.length(), maxLength));
    }
}