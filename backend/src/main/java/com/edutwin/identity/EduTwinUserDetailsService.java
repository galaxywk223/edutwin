package com.edutwin.identity;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class EduTwinUserDetailsService implements UserDetailsService {

    private final AccountRepository accountRepository;

    public EduTwinUserDetailsService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return accountRepository.findByUsername(username)
                .filter(EduTwinPrincipal::enabled)
                .orElseThrow(() -> new UsernameNotFoundException("account not found"));
    }
}
