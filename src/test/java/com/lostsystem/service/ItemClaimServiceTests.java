package com.lostsystem.service;

import com.lostsystem.dto.CreateClaimRequest;
import com.lostsystem.entity.*;
import com.lostsystem.exception.BusinessException;
import com.lostsystem.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mock-based service tests: database locking and rollback require a separate database integration run. */
class ItemClaimServiceTests {
    private LostPropertyMapper properties;
    private LostRegisterMapper registrations;
    private ItemClaimMapper claims;
    private LostPropertyIndexService index;
    private ItemClaimService service;
    private LostPropertyEntity property;
    private LostRegisterEntity registration;
    private ItemClaimEntity claim;
    @BeforeEach void setup() {
        properties=mock(LostPropertyMapper.class); registrations=mock(LostRegisterMapper.class); claims=mock(ItemClaimMapper.class); index=mock(LostPropertyIndexService.class);
        service=new ItemClaimService(properties,registrations,claims,index);
        property=new LostPropertyEntity(); property.setId(8L); property.setUserId(2L); property.setStatus("AVAILABLE"); property.setVersion(3L);
        registration=new LostRegisterEntity(); registration.setId(9L); registration.setUserId(7L); registration.setStatus("SEARCHING"); registration.setVersion(4L);
        claim=new ItemClaimEntity(); claim.setId(21L); claim.setLostPropertyId(8L); claim.setLostRegisterId(9L); claim.setClaimantUserId(7L); claim.setStatus("PENDING");
        when(properties.findByIdForUpdate(8L)).thenReturn(property);
        when(registrations.findOwnedByIdForUpdate(9L,7L)).thenReturn(registration);
        when(claims.selectById(21L)).thenReturn(claim); when(claims.findByIdForUpdate(21L)).thenReturn(claim);
    }
    @Test void createUsesCurrentUserAndRejectsOwnFoundItem() {
        assertEquals(400,assertThrows(BusinessException.class,()->service.create(2L,new CreateClaimRequest(8L,9L,"proof"))).getCode());
        verify(claims,never()).insert(any(ItemClaimEntity.class));
    }
    @Test void foreignLostRegistrationCannotBeUsed() {
        when(registrations.findOwnedByIdForUpdate(9L,7L)).thenReturn(null);
        assertEquals(404,assertThrows(BusinessException.class,()->service.create(7L,new CreateClaimRequest(8L,9L,"proof"))).getCode());
        verify(claims,never()).insert(any(ItemClaimEntity.class));
    }
    @Test void duplicateActiveSubmissionReusesClaimEvenAfterReservation() {
        property.setStatus("RESERVED"); claim.setStatus("APPROVED");
        when(claims.findActiveForUpdate(8L,9L,7L)).thenReturn(claim);
        assertEquals(21L,service.create(7L,new CreateClaimRequest(8L,9L,"proof")).id()); verify(claims,never()).insert(any(ItemClaimEntity.class));
    }
    @Test void approveUpdatesBothItemsThenClaimAndSchedulesIndexSync() {
        when(properties.reserveForClaim(8L,2L,3L)).thenReturn(1); when(registrations.startHandover(9L,7L,4L)).thenReturn(1); when(claims.approvePending(21L)).thenReturn(1);
        assertEquals("APPROVED",service.approve(2L,21L).status());
        var order=inOrder(properties,registrations,claims,index);
        order.verify(claims).selectById(21L); order.verify(properties).findByIdForUpdate(8L); order.verify(registrations).findOwnedByIdForUpdate(9L,7L); order.verify(claims).findByIdForUpdate(21L);
        order.verify(properties).reserveForClaim(8L,2L,3L); order.verify(registrations).startHandover(9L,7L,4L); order.verify(claims).approvePending(21L); order.verify(index).syncAfterCommit(8L);
    }
    @Test void claimantCannotApproveAndOutsiderCannotReceive() {
        assertEquals(404,assertThrows(BusinessException.class,()->service.approve(7L,21L)).getCode());
        assertEquals(404,assertThrows(BusinessException.class,()->service.receive(99L,21L)).getCode()); verifyNoInteractions(index);
    }
    @Test void approvalConflictStopsFollowingUpdatesAndIndex() {
        assertEquals(409,assertThrows(BusinessException.class,()->service.approve(2L,21L)).getCode());
        verify(registrations,never()).startHandover(anyLong(),anyLong(),anyLong()); verifyNoInteractions(index);
    }
    @Test void approvedActionIsIdempotent() {
        claim.setStatus("APPROVED"); assertEquals("APPROVED",service.approve(2L,21L).status());
        verify(properties,never()).reserveForClaim(anyLong(),anyLong(),anyLong()); verifyNoInteractions(index);
    }
    @Test void handoverRequiresApprovalAndConsistentItemStates() {
        assertEquals(409,assertThrows(BusinessException.class,()->service.handover(2L,21L)).getCode());
        claim.setStatus("APPROVED"); assertThrows(BusinessException.class,()->service.handover(2L,21L));
        property.setStatus("RESERVED"); registration.setStatus("IN_HANDOVER"); when(claims.markHandoverPending(21L)).thenReturn(1);
        assertEquals("HANDOVER_PENDING",service.handover(2L,21L).status());
    }
    @Test void receiveCompletesBothRecordsAndIsIdempotent() {
        claim.setStatus("HANDOVER_PENDING"); property.setStatus("RESERVED"); registration.setStatus("IN_HANDOVER");
        when(properties.markReturned(8L,2L,3L)).thenReturn(1); when(registrations.markRecovered(9L,7L,4L)).thenReturn(1); when(claims.completeHandover(21L,7L)).thenReturn(1);
        assertEquals("COMPLETED",service.receive(7L,21L).status()); assertEquals("COMPLETED",service.receive(7L,21L).status());
        verify(properties,times(1)).markReturned(8L,2L,3L); verify(registrations,times(1)).markRecovered(9L,7L,4L); verify(index,times(1)).syncAfterCommit(8L);
    }
    @Test void receivedOrHandedOverClaimCannotBeCancelled() {
        claim.setStatus("HANDOVER_PENDING"); assertEquals(409,assertThrows(BusinessException.class,()->service.cancel(7L,21L)).getCode());
        verify(properties,never()).releaseReservation(anyLong(),anyLong(),anyLong()); verifyNoInteractions(index);
    }
    @Test void approvedCancellationReleasesBothItemsAndSyncs() {
        claim.setStatus("APPROVED"); property.setStatus("RESERVED"); registration.setStatus("IN_HANDOVER");
        when(properties.releaseReservation(8L,2L,3L)).thenReturn(1); when(registrations.resumeSearching(9L,7L,4L)).thenReturn(1); when(claims.cancelApproved(21L)).thenReturn(1);
        assertEquals("CANCELLED",service.cancel(7L,21L).status()); verify(index).syncAfterCommit(8L);
    }
    @Test void outsidersCannotReadDetailOrContact() {
        assertEquals(404,assertThrows(BusinessException.class,()->service.detail(99L,21L)).getCode());
        assertEquals(404,assertThrows(BusinessException.class,()->service.contact(99L,21L)).getCode());
        verify(claims).findDetailForParticipant(21L,99L); verify(claims).findContactForParticipant(21L,99L);
    }
    @Test void contactOnlyAvailableAfterApprovalWithCompleteProfile() {
        var contact = new com.lostsystem.vo.ClaimContactVO();
        contact.setStatus("PENDING"); contact.setRealName(" 张三 "); contact.setPhone(" 13800000000 ");
        when(claims.findContactForParticipant(21L,7L)).thenReturn(contact);
        assertEquals(403,assertThrows(BusinessException.class,()->service.contact(7L,21L)).getCode());
        contact.setStatus("APPROVED"); assertEquals("13800000000",service.contact(7L,21L).getPhone());
        contact.setPhone(" "); assertEquals(409,assertThrows(BusinessException.class,()->service.contact(7L,21L)).getCode());
    }

}
