package com.devak.mrdaebakdinner.service;

import com.devak.mrdaebakdinner.dto.CustomerLoginDTO;
import com.devak.mrdaebakdinner.dto.CustomerSessionDTO;
import com.devak.mrdaebakdinner.dto.CustomerSignUpDTO;
import com.devak.mrdaebakdinner.entity.CustomerEntity;
import com.devak.mrdaebakdinner.exception.CustomerNotFoundException;
import com.devak.mrdaebakdinner.exception.DuplicateLoginIdException;
import com.devak.mrdaebakdinner.exception.IncorrectPasswordException;
import com.devak.mrdaebakdinner.mapper.CustomerMapper;
import com.devak.mrdaebakdinner.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;

    // Controller로부터 온 login요청 처리
    public CustomerSessionDTO login(CustomerLoginDTO customerLoginDTO) {
        // ID 존재 확인
        CustomerEntity customerEntity = customerRepository.findByLoginId(customerLoginDTO.getLoginId())
                        .orElseThrow(() -> new CustomerNotFoundException("ID가 존재하지 않습니다."));
        // incorrect password
        if (!customerEntity.getPassword().equals(customerLoginDTO.getPassword())) {
            throw new IncorrectPasswordException("비밀번호가 일치하지 않습니다.");
        }

        return CustomerMapper.toCustomerSessionDTO(customerEntity);
    }

    public void signUp(CustomerSignUpDTO customerSignUpDTO) {
        // ID 존재 확인
        if (customerRepository.findByLoginId(customerSignUpDTO.getLoginId()).isPresent()) {
            throw new DuplicateLoginIdException("이미 존재하는 사용자입니다.");
        }
        // 존재하지 않는 ID면 회원가입 시도
        customerRepository.save(CustomerMapper.toCustomerEntity(customerSignUpDTO));
    }

}
