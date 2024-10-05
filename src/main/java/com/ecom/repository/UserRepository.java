package com.ecom.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ecom.model.UserDtls;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<UserDtls, Integer> {

	public UserDtls findByEmail(String email);

	public List<UserDtls> findByRole(String role);

	public UserDtls findByResetToken(String token);

	public Boolean existsByEmail(String email);


	public Page<UserDtls> findByRole(Pageable pageable,String role);

	public Page<UserDtls> findByRoleAndNameContainingIgnoreCaseOrEmailContainingIgnoreCase(Pageable pageable,String role,String name,String email);
}
