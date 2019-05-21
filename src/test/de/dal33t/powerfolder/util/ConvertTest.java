package de.dal33t.powerfolder.util;

import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.Member;
import de.dal33t.powerfolder.light.MemberInfo;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.Assert.assertEquals;

public class ConvertTest {

    @Test
    public void convert2BytesTest() {
        int number = 123456789;
        byte[] convertedNumber = Convert.convert2Bytes(number);
        byte[] expectedNumber = ByteBuffer.allocate(4).putInt(number).array();
        assertEquals(expectedNumber[0], convertedNumber[0]);
        assertEquals(expectedNumber[1], convertedNumber[1]);
        assertEquals(expectedNumber[2], convertedNumber[2]);
        assertEquals(expectedNumber[3], convertedNumber[3]);
    }

    @Test(expected = NullPointerException.class)
    public void convert2BytesNullInputTest() {
        //Method does not handle null inputs
        Integer number = null;
        Convert.convert2Bytes(number);
    }

    @Test
    public void convert2BytesDifferentInputsTest() {
        Integer number = new Integer(2340000);
        byte[] convertedNumber = Convert.convert2Bytes(number);
        byte[] expectedNumber = ByteBuffer.allocate(4).putInt(number).array();
        assertEquals(expectedNumber[0], convertedNumber[0]);
        assertEquals(expectedNumber[1], convertedNumber[1]);
        assertEquals(expectedNumber[2], convertedNumber[2]);
        assertEquals(expectedNumber[3], convertedNumber[3]);

        int max = Integer.MAX_VALUE;
        byte[] convertedMax = Convert.convert2Bytes(max);
        byte[] expectedMax = ByteBuffer.allocate(4).putInt(max).array();
        assertEquals(expectedMax[0], convertedMax[0]);
        assertEquals(expectedMax[1], convertedMax[1]);
        assertEquals(expectedMax[2], convertedMax[2]);
        assertEquals(expectedMax[3], convertedMax[3]);

        int minValue = Integer.MIN_VALUE;
        byte[] convertedMin = Convert.convert2Bytes(minValue);
        byte[] expectedMin = ByteBuffer.allocate(4).putInt(minValue).array();
        assertEquals(expectedMin[0], convertedMin[0]);
        assertEquals(expectedMin[1], convertedMin[1]);
        assertEquals(expectedMin[2], convertedMin[2]);
        assertEquals(expectedMin[3], convertedMin[3]);

        int zero = 0;
        byte[] convertedZero = Convert.convert2Bytes(minValue);
        assertEquals(-128, convertedZero[0]);
        assertEquals(0, convertedZero[1]);
        assertEquals(0, convertedZero[2]);
        assertEquals(0, convertedZero[3]);
    }

    @Test
    public void convert2IntTest() {
        byte[] arrayToConvert = {25, 32, 12, 22};
        assertEquals(421530646, Convert.convert2Int(arrayToConvert));

        byte[] anotherArray = {-12, 87, 11, 23};
        assertEquals(-195622121, Convert.convert2Int(anotherArray));

        byte[] zeroArray = {0, 0, 0, 0};
        assertEquals(0, Convert.convert2Int(zeroArray));

        byte[] moreThanFour = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        assertEquals(151653132, Convert.convert2Int(moreThanFour));

    }

    @Test(expected = NullPointerException.class)
    public void convert2IntNullTest() {
        //Method does not handle null as an input
        Convert.convert2Int(null);
    }


    @Test(expected = NullPointerException.class)
    public void asMemberInfosArrayNullTest() {
        Member[] members = null;
        Convert.asMemberInfos(members);
    }

    @Test
    public void asMemberInfosArrayTest(){
        Controller controller = new Controller();

        MemberInfo firstMemberInfo = new MemberInfo("First nick", "First id", "First network");
        Member firstMember = new Member(controller, firstMemberInfo);

        MemberInfo secondMemberInfo = new MemberInfo("Second nick", "Second id", "Second network");
        Member secondMember = new Member(controller, secondMemberInfo);

        MemberInfo thirdMemberInfo = new MemberInfo("Third nick", "Third id", "Third network");
        Member thirdMember = new Member(controller, thirdMemberInfo);

        Member[] members = {firstMember, secondMember, thirdMember};

        MemberInfo[] memberInfos = Convert.asMemberInfos(members);

        assertEquals(memberInfos[0], firstMemberInfo);
        assertEquals(memberInfos[1], secondMemberInfo);
        assertEquals(memberInfos[2], thirdMemberInfo);

    }

    @Test(expected = NullPointerException.class)
    public void asMemberInfosNullMembersTest(){
        //Method does not handle the case when one of the memberInfo is null
        Controller controller = new Controller();

        MemberInfo firstMemberInfo = null;
        Member firstMember = new Member(controller, firstMemberInfo);

        MemberInfo secondMemberInfo = new MemberInfo("Second nick", "Second id", "Second network");
        Member secondMember = new Member(controller, secondMemberInfo);

        MemberInfo thirdMemberInfo = new MemberInfo("Third nick", "Third id", "Third network");
        Member thirdMember = new Member(controller, thirdMemberInfo);

        Member[] members = {firstMember, secondMember, thirdMember};

        MemberInfo[] memberInfos = Convert.asMemberInfos(members);

        assertEquals(memberInfos[0], firstMemberInfo);
        assertEquals(memberInfos[1], secondMemberInfo);
        assertEquals(memberInfos[2], thirdMemberInfo);

    }

    @Test
    public void asMemberInfosArrayManyElementsTest(){
        Controller controller = new Controller();
        Member[] members = new Member[10000];
        for (int index = 0; index < 10000; index++) {
            MemberInfo memberInfo = new MemberInfo(index + "Nick", index + "id", index + "network");
            members[index] = new Member(controller, memberInfo);
        }
        MemberInfo[] memberInfos = Convert.asMemberInfos(members);
        for (int i = 0; i < 10000; i++) {
            assertEquals(memberInfos[i], members[i].getInfo());
        }
    }

    @Test(expected = NullPointerException.class)
    public void asMemberInfosArrayEmptyArray(){
        //Does not handle null when memberInfo is null
        Member[] members = new Member[10];
        Convert.asMemberInfos(members);
    }

    @Test(expected = NullPointerException.class)
    public void asMemberInfosListNullTest(){
        //Method does not handle null
        List members = null;
        Convert.asMemberInfos(members);
    }

    @Test
    public void asMemberInfosListTest() {
        Controller controller = new Controller();

        MemberInfo firstMemberInfo = new MemberInfo("First nick", "First id", "First network");
        Member firstMember = new Member(controller, firstMemberInfo);

        MemberInfo secondMemberInfo = new MemberInfo("Second nick", "Second id", "Second network");
        Member secondMember = new Member(controller, secondMemberInfo);

        MemberInfo thirdMemberInfo = new MemberInfo("Third nick", "Third id", "Third network");
        Member thirdMember = new Member(controller, thirdMemberInfo);

        List members = Arrays.asList(firstMember, secondMember, thirdMember);

        List memberInfos = Convert.asMemberInfos(members);

        assertEquals(memberInfos.get(0), firstMemberInfo);
        assertEquals(memberInfos.get(1), secondMemberInfo);
        assertEquals(memberInfos.get(2), thirdMemberInfo);
    }

    @Test(expected = NullPointerException.class)
    public void asMemberInfosListNullMemberTest() {
        Controller controller = new Controller();

        MemberInfo firstMemberInfo = null;
        Member firstMember = new Member(controller, firstMemberInfo);

        MemberInfo secondMemberInfo = new MemberInfo("Second nick", "Second id", "Second network");
        Member secondMember = new Member(controller, secondMemberInfo);

        MemberInfo thirdMemberInfo = new MemberInfo("Third nick", "Third id", "Third network");
        Member thirdMember = new Member(controller, thirdMemberInfo);

        List members = Arrays.asList(firstMember, secondMember, thirdMember);

        List memberInfos = Convert.asMemberInfos(members);

        assertEquals(memberInfos.get(0), firstMemberInfo);
        assertEquals(memberInfos.get(1), secondMemberInfo);
        assertEquals(memberInfos.get(2), thirdMemberInfo);
    }

    @Test
    public void asMemberInfosListEmptyListTest() {
        List members = new ArrayList<Member>();
        assertEquals(0, Convert.asMemberInfos(members).size());
    }


    @Test
    public void convertToUTCTest() {
        Date date = new Date();
        long newDate = date.getTime() - (Calendar.getInstance().get(Calendar.ZONE_OFFSET)
                + Calendar.getInstance().get(Calendar.DST_OFFSET));
        assertEquals(newDate, Convert.convertToUTC(date));
    }

    @Test(expected = NullPointerException.class)
    public void convertToUtcNullTest() {
        Date date = null;
        Convert.convertToUTC(date);
    }

    @Test
    public void convertToGlobalPrecision() {

        long losesPrecision = 1999999999999999999L;
        assertEquals(1999999999999998000L, Convert.convertToGlobalPrecision(losesPrecision));

        long stillLosesPrecision = 1999999999999999L;
        assertEquals(1999999999998000L, Convert.convertToGlobalPrecision(stillLosesPrecision));

        long doesNotLosePrecision = 1000000000000000L;
        assertEquals(1000000000000000L, Convert.convertToGlobalPrecision(doesNotLosePrecision));

        long zero = 0;
        assertEquals(0, Convert.convertToGlobalPrecision(zero));

        long maxValue = Long.MAX_VALUE;
        assertEquals(9223372036854774000L ,Convert.convertToGlobalPrecision(maxValue));

        long minValue = Long.MIN_VALUE;
        assertEquals(-9223372036854774000L ,Convert.convertToGlobalPrecision(minValue));

    }

}