<!-- query to fetch employee with second highest salary against each department -->
from employee emp join  (select max(e.salary) as salary, e.dept_name
                        from employee e join (select e.dept_name, max(e.salary)
                                from employee e
                                group by e.dept_name
                            ) d on e.dept_name = e.dept_name
                            and e.salary < d.salary
                            
                        group by e.dept_name) dept  on e.dept_name = e.dept_name
                                                    and e.salary = d.salary;


<1-- Employee from each department with max salary -->
SELECT dept_id, emp_id, emp_name, salary
FROM (
    SELECT 
        dept_id, 
        emp_id, 
        emp_name, 
        salary,
        RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rnk
    FROM employee
) ranked
WHERE rnk = 1;

Logical order of operations (the general rule)
FROM → (JOIN) → WHERE → GROUP BY → HAVING → 
WINDOW FUNCTIONS → SELECT (outer) → WHERE (outer, if wrapped) → ORDER BY

SELECT e.dept_id, e.emp_id, e.emp_name, e.salary
FROM employee e
JOIN (
    SELECT dept_id, MAX(salary) AS max_salary
    FROM employee
    GROUP BY dept_id
) m ON e.dept_id = m.dept_id AND e.salary = m.max_salary;3